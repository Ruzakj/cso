package id.ric.isotocso;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.content.ClipData;
import android.provider.DocumentsContract;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.DecimalFormat;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int PICK_ISO = 10, PICK_OUTPUT_DIR = 11;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Uri inputUri, outputUri, outputDirUri;
    private final List<Uri> inputQueue = new ArrayList<>();
    private final List<String> nameQueue = new ArrayList<>();
    private int queueIndex = 0, queueCsoLevel = 6;
    private boolean queueRunning = false;
    private String inputName = "game.iso";
    private TextView fileText, levelText, statusText, statsText;
    private Button chooseButton, startButton, cancelButton;
    private ProgressBar progress;
    private SeekBar levelBar;
    private RadioButton csoOption, chdOption;
    private boolean outputAsChd;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(245,245,250));
        buildUi();
    }

    private void buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(30), pad, pad); root.setBackgroundColor(Color.rgb(245,245,250));
        TextView title = text("CISO · PS2 MAX", 30, true); root.addView(title);
        TextView sub = text("PS2 ISO → CHD lossless maksimum · full offline", 14, false);
        sub.setTextColor(Color.DKGRAY); root.addView(sub, margins(dp(0),dp(6),0,dp(28)));

        fileText = text("Belum ada ISO dipilih", 16, true); root.addView(fileText, margins(0,0,0,dp(12)));
        chooseButton = button("Pilih file ISO"); chooseButton.setOnClickListener(v -> pickIso()); root.addView(chooseButton);
        Button cutsceneButton=button("Kelola cutscene PS2");
        cutsceneButton.setOnClickListener(v->startActivity(new Intent(this,CutsceneActivity.class)));
        root.addView(cutsceneButton,margins(0,dp(8),0,0));

        TextView formatLabel = text("Format hasil", 16, true); root.addView(formatLabel, margins(0,dp(28),0,dp(6)));
        RadioGroup formats = new RadioGroup(this); formats.setOrientation(RadioGroup.HORIZONTAL);
        csoOption = new RadioButton(this); csoOption.setId(View.generateViewId()); csoOption.setText("CSO · kompatibel luas");
        chdOption = new RadioButton(this); chdOption.setId(View.generateViewId()); chdOption.setText("PS2 MAX · CHD lossless");
        formats.addView(csoOption); formats.addView(chdOption); formats.check(chdOption.getId()); root.addView(formats);
        formats.setOnCheckedChangeListener((group, id) -> {
            boolean cso=id==csoOption.getId();
            levelBar.setVisibility(cso?View.VISIBLE:View.GONE);
            levelText.setVisibility(cso?View.VISIBLE:View.GONE);
            startButton.setText(cso?"Pilih lokasi CSO & mulai":"Pilih lokasi CHD & mulai");
        });

        levelText = text("Level kompresi CSO: 6 · Seimbang", 16, true); root.addView(levelText, margins(0,dp(24),0,dp(4)));
        levelBar = new SeekBar(this); levelBar.setMax(8); levelBar.setProgress(5);
        levelBar.setVisibility(View.GONE); levelText.setVisibility(View.GONE); startButton = button("Pilih lokasi CHD & mulai");
        levelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b,int p,boolean u){ int l=p+1; levelText.setText("Level kompresi CSO: "+l+" · "+(l<=3?"Cepat":l<=6?"Seimbang":"Maksimal")); }
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        }); root.addView(levelBar);

        startButton.setEnabled(false);
        startButton.setOnClickListener(v -> createOutput()); root.addView(startButton, margins(0,dp(22),0,0));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
        root.addView(progress, margins(0,dp(24),0,dp(12)));
        statusText = text("Siap", 16, true); root.addView(statusText);
        statsText = text("File asli tetap aman dan tidak akan diubah.", 14, false); statsText.setTextColor(Color.DKGRAY);
        root.addView(statsText, margins(0,dp(7),0,dp(12)));
        cancelButton = button("Batalkan"); cancelButton.setVisibility(View.GONE);
        cancelButton.setOnClickListener(v -> { cancelled.set(true); statusText.setText("Membatalkan…"); cancelButton.setEnabled(false); });
        root.addView(cancelButton);
        setContentView(root);
    }

    private void pickIso() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream",
                        "application/x-iso9660-image",
                        "application/x-cd-image",
                        "application/x-iso-image"
                });
        startActivityForResult(i, PICK_ISO);
    }
    private void createOutput() {
        if(inputQueue.isEmpty() && inputUri!=null){ inputQueue.add(inputUri); nameQueue.add(inputName); }
        if(inputQueue.isEmpty()) return;
        outputAsChd = formatsChdSelected();
        queueCsoLevel = levelBar.getProgress()+1;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION |
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(i, PICK_OUTPUT_DIR);
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data); if(result!=RESULT_OK || data==null)return;
        if(req==PICK_ISO){
            inputQueue.clear(); nameQueue.clear();
            ClipData clips=data.getClipData();
            if(clips!=null){
                for(int n=0;n<clips.getItemCount();n++) addIsoToQueue(clips.getItemAt(n).getUri());
            } else if(data.getData()!=null) addIsoToQueue(data.getData());
            if(inputQueue.isEmpty()){ statusText.setText("Tidak ada ISO valid dipilih"); return; }
            inputUri=inputQueue.get(0); inputName=nameQueue.get(0);
            fileText.setText(inputQueue.size()==1?inputName:inputQueue.size()+" ISO dalam antrian");
            startButton.setEnabled(true);
            statusText.setText(inputQueue.size()==1?"ISO siap dikompres":"Antrian siap · "+inputQueue.size()+" file");
            statsText.setText(inputQueue.size()==1?"File asli tetap aman dan tidak akan diubah.":"Diproses berurutan otomatis: 1 → "+inputQueue.size());
        } else if(req==PICK_OUTPUT_DIR && data.getData()!=null){
            outputDirUri=data.getData();
            try{ getContentResolver().takePersistableUriPermission(outputDirUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION); }catch(Exception ignored){}
            queueIndex=0; queueRunning=true; cancelled.set(false); processNextQueueItem();
        }
    }
    private void addIsoToQueue(Uri uri){
        String n=nameOf(uri);
        if(n.toLowerCase(Locale.ROOT).endsWith(".iso")){ inputQueue.add(uri); nameQueue.add(n); }
    }
    private void processNextQueueItem(){
        if(!queueRunning) return;
        if(cancelled.get()){ queueRunning=false; setBusy(false); statusText.setText("Antrian dibatalkan"); return; }
        if(queueIndex>=inputQueue.size()){
            queueRunning=false; setBusy(false); progress.setProgress(1000);
            statusText.setText("Semua antrian selesai");
            statsText.setText(inputQueue.size()+" file berhasil diproses.");
            return;
        }
        inputUri=inputQueue.get(queueIndex); inputName=nameQueue.get(queueIndex);
        String base=inputName.toLowerCase(Locale.ROOT).endsWith(".iso")?inputName.substring(0,inputName.length()-4):inputName;
        String ext=outputAsChd?".chd":".cso";
        try{
            outputUri=DocumentsContract.createDocument(getContentResolver(),outputDirUri,
                    "application/octet-stream",base+ext);
            if(outputUri==null) throw new IOException("Gagal membuat output "+base+ext);
            fileText.setText("Antrian "+(queueIndex+1)+"/"+inputQueue.size()+" · "+inputName);
            runCompression();
        }catch(Exception e){
            queueRunning=false; setBusy(false); statusText.setText("Gagal membuat output");
            statsText.setText(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());
        }
    }
    private void runCompression() {
        setBusy(true); cancelled.set(false); progress.setProgress(0); long uiStart=System.currentTimeMillis();
        final boolean makeChd=outputAsChd; final int csoLevel=queueRunning?queueCsoLevel:levelBar.getProgress()+1;
        worker.execute(() -> {
            try {
                long inputBytes, outputBytes;
                if(makeChd){
                    long[] sizes=compressChd(); inputBytes=sizes[0]; outputBytes=sizes[1];
                } else {
                CsoCompressor.Result r=CsoCompressor.compress(getContentResolver(),inputUri,outputUri,csoLevel,cancelled,
                    (done,total,written,started)->runOnUiThread(()->{
                        progress.setProgress((int)(done*1000/total)); double sec=Math.max(.001,(System.currentTimeMillis()-started)/1000d);
                        statusText.setText("Mengompres… "+(done*100/total)+"%");
                        statsText.setText(size(done)+" / "+size(total)+"  ·  "+size((long)(done/sec))+"/dtk\\nOutput sementara: "+size(written));
                    })); inputBytes=r.inputBytes(); outputBytes=r.outputBytes();
                }
                runOnUiThread(()->{ progress.setProgress(1000); long saved=inputBytes-outputBytes; double ratio=outputBytes*100d/inputBytes;\n                    statsText.setText("Hasil "+size(outputBytes)+" · "+new DecimalFormat("0.0").format(ratio)+"% dari ISO\\nHemat "+size(Math.max(0,saved))+" · "+((System.currentTimeMillis()-uiStart)/1000)+" detik");\n                    if(queueRunning){ queueIndex++; statusText.setText("Selesai · lanjut antrian berikutnya"); processNextQueueItem(); }\n                    else { setBusy(false); statusText.setText("Selesai & tervalidasi"); } });
            } catch(Exception e) {
                if(outputUri!=null) try{ getContentResolver().delete(outputUri,null,null); }catch(Exception ignored){}
                runOnUiThread(()->{queueRunning=false;setBusy(false);progress.setProgress(0);statusText.setText(e instanceof CsoCompressor.CancelledException?"Dibatalkan":"Gagal di antrian "+(queueIndex+1));statsText.setText(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());});
            }
        });
    }
    private long[] compressChd() throws IOException {
        File cache=getExternalCacheDir()!=null?getExternalCacheDir():getCacheDir();
        File input=new File(cache,"chd-input.iso"), output=new File(cache,"chd-output.chd");
        try {
            long total=uriSize(inputUri); copyUriToFile(inputUri,input,total);
            if(cancelled.get()) throw new CsoCompressor.CancelledException();
            runOnUiThread(()->{progress.setIndeterminate(true);statusText.setText("Membuat CHD…");statsText.setText("PS2 MAX · LZMA/ZLIB/HUFF/FLAC · hunk 1 MiB · lossless. Proses lebih lama untuk ukuran minimum.");});
            new com.chdman.utils.Chdman().createDvd(input,output);
            if(cancelled.get()) throw new CsoCompressor.CancelledException();
            runOnUiThread(()->{progress.setIndeterminate(false);statusText.setText("Menyimpan CHD…");});
            try(InputStream in=new FileInputStream(output);OutputStream out=getContentResolver().openOutputStream(outputUri,"wt")){
                if(out==null)throw new IOException("Lokasi output tidak dapat ditulis"); copy(in,out,output.length(),"Menyimpan CHD…");
            }
            return new long[]{input.length(),output.length()};
        } finally { input.delete(); output.delete(); runOnUiThread(()->progress.setIndeterminate(false)); }
    }
    private void copyUriToFile(Uri uri,File target,long total)throws IOException{
        try(InputStream in=getContentResolver().openInputStream(uri);OutputStream out=new FileOutputStream(target)){
            if(in==null)throw new IOException("ISO tidak dapat dibuka"); copy(in,out,total,"Menyiapkan ISO…");
        }
    }
    private void copy(InputStream in,OutputStream out,long total,String label)throws IOException{
        byte[] buffer=new byte[1024*1024];long done=0;int read;
        while((read=in.read(buffer))!=-1){if(cancelled.get())throw new CsoCompressor.CancelledException();if(read==0)continue;out.write(buffer,0,read);done+=read;long value=done;
            runOnUiThread(()->{progress.setProgress(total>0?(int)(value*1000/total):0);statusText.setText(label+" "+(total>0?value*100/total:0)+"%");statsText.setText(size(value)+(total>0?" / "+size(total):""));});}
        out.flush();
    }
    private boolean formatsChdSelected(){ return chdOption!=null && chdOption.isChecked(); }
    private long uriSize(Uri uri){try(android.os.ParcelFileDescriptor p=getContentResolver().openFileDescriptor(uri,"r")){return p==null?-1:p.getStatSize();}catch(Exception e){return -1;}}
    private void setBusy(boolean busy){ chooseButton.setEnabled(!busy);startButton.setEnabled(!busy&&inputUri!=null);levelBar.setEnabled(!busy);csoOption.setEnabled(!busy);chdOption.setEnabled(!busy);cancelButton.setVisibility(busy?View.VISIBLE:View.GONE);cancelButton.setEnabled(busy); if(busy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    private String nameOf(Uri uri){ try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "game.iso"; }
    private static String size(long n){ if(n<1024)return n+" B"; double v=n;String[]u={"KB","MB","GB","TB"};int i=-1;do{v/=1024;i++;}while(v>=1024&&i<u.length-1);return String.format(Locale.getDefault(),"%.1f %s",v,u[i]); }
    private TextView text(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.rgb(24,24,32));if(bold)v.setTypeface(null,1);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(52));return b;}
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(l,t,r,b);return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){ super.onDestroy(); if(isFinishing())cancelled.set(true); }
}
