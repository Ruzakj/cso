package id.ric.isotocso;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import java.text.DecimalFormat;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int PICK_ISO = 10, CREATE_CSO = 11;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private Uri inputUri, outputUri;
    private String inputName = "game.iso";
    private TextView fileText, levelText, statusText, statsText;
    private Button chooseButton, startButton, cancelButton;
    private ProgressBar progress;
    private SeekBar levelBar;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(245,245,250));
        buildUi();
    }

    private void buildUi() {
        int pad = dp(22);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, dp(30), pad, pad); root.setBackgroundColor(Color.rgb(245,245,250));
        TextView title = text("ISO → CSO", 30, true); root.addView(title);
        TextView sub = text("Kompres game PSP langsung di perangkat · full offline", 14, false);
        sub.setTextColor(Color.DKGRAY); root.addView(sub, margins(dp(0),dp(6),0,dp(28)));

        fileText = text("Belum ada ISO dipilih", 16, true); root.addView(fileText, margins(0,0,0,dp(12)));
        chooseButton = button("Pilih file ISO"); chooseButton.setOnClickListener(v -> pickIso()); root.addView(chooseButton);

        levelText = text("Level kompresi: 6 · Seimbang", 16, true); root.addView(levelText, margins(0,dp(30),0,dp(4)));
        levelBar = new SeekBar(this); levelBar.setMax(8); levelBar.setProgress(5);
        levelBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar b,int p,boolean u){ int l=p+1; levelText.setText("Level kompresi: "+l+" · "+(l<=3?"Cepat":l<=6?"Seimbang":"Maksimal")); }
            public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){}
        }); root.addView(levelBar);

        startButton = button("Pilih lokasi hasil & mulai"); startButton.setEnabled(false);
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
                .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                        "application/octet-stream",
                        "application/x-iso9660-image",
                        "application/x-cd-image",
                        "application/x-iso-image"
                });
        startActivityForResult(i, PICK_ISO);
    }
    private void createOutput() {
        String base = inputName.toLowerCase(Locale.ROOT).endsWith(".iso") ? inputName.substring(0,inputName.length()-4) : inputName;
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, base + ".cso");
        startActivityForResult(i, CREATE_CSO);
    }
    @Override protected void onActivityResult(int req,int result,Intent data) {
        super.onActivityResult(req,result,data); if(result!=RESULT_OK || data==null || data.getData()==null)return;
        Uri uri=data.getData();
        if(req==PICK_ISO){
            String selectedName=nameOf(uri);
            if(!selectedName.toLowerCase(Locale.ROOT).endsWith(".iso")){
                statusText.setText("File harus berformat .iso");
                statsText.setText("Pilih image game PSP dengan ekstensi ISO.");
                return;
            }
            inputUri=uri; inputName=selectedName; fileText.setText(inputName);
            startButton.setEnabled(true); statusText.setText("ISO siap dikompres");
        }
        else if(req==CREATE_CSO){ outputUri=uri; runCompression(); }
    }
    private void runCompression() {
        setBusy(true); cancelled.set(false); progress.setProgress(0); long uiStart=System.currentTimeMillis();
        worker.execute(() -> {
            try {
                CsoCompressor.Result r=CsoCompressor.compress(getContentResolver(),inputUri,outputUri,levelBar.getProgress()+1,cancelled,
                    (done,total,written,started)->runOnUiThread(()->{
                        progress.setProgress((int)(done*1000/total)); double sec=Math.max(.001,(System.currentTimeMillis()-started)/1000d);
                        statusText.setText("Mengompres… "+(done*100/total)+"%");
                        statsText.setText(size(done)+" / "+size(total)+"  ·  "+size((long)(done/sec))+"/dtk\nOutput sementara: "+size(written));
                    }));
                runOnUiThread(()->{ setBusy(false); progress.setProgress(1000); statusText.setText("Selesai & tervalidasi");
                    long saved=r.inputBytes()-r.outputBytes(); double ratio=r.outputBytes()*100d/r.inputBytes();
                    statsText.setText("Hasil "+size(r.outputBytes())+" · "+new DecimalFormat("0.0").format(ratio)+"% dari ISO\nHemat "+size(Math.max(0,saved))+" · "+((System.currentTimeMillis()-uiStart)/1000)+" detik"); });
            } catch(Exception e) {
                if(outputUri!=null) try{ getContentResolver().delete(outputUri,null,null); }catch(Exception ignored){}
                runOnUiThread(()->{setBusy(false);progress.setProgress(0);statusText.setText(e instanceof CsoCompressor.CancelledException?"Dibatalkan":"Gagal");statsText.setText(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());});
            }
        });
    }
    private void setBusy(boolean busy){ chooseButton.setEnabled(!busy);startButton.setEnabled(!busy&&inputUri!=null);levelBar.setEnabled(!busy);cancelButton.setVisibility(busy?View.VISIBLE:View.GONE);cancelButton.setEnabled(busy); if(busy)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    private String nameOf(Uri uri){ try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "game.iso"; }
    private static String size(long n){ if(n<1024)return n+" B"; double v=n;String[]u={"KB","MB","GB","TB"};int i=-1;do{v/=1024;i++;}while(v>=1024&&i<u.length-1);return String.format(Locale.getDefault(),"%.1f %s",v,u[i]); }
    private TextView text(String s,int sp,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(sp);v.setTextColor(Color.rgb(24,24,32));if(bold)v.setTypeface(null,1);return v;}
    private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);b.setTextSize(15);b.setMinHeight(dp(52));return b;}
    private LinearLayout.LayoutParams margins(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(l,t,r,b);return p;}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override protected void onDestroy(){ super.onDestroy(); if(isFinishing())cancelled.set(true); }
}
