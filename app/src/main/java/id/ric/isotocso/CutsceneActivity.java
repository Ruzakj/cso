package id.ric.isotocso;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class CutsceneActivity extends Activity {
    private static final int PICK=31,CREATE=32;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled=new AtomicBoolean();
    private Uri isoUri;
    private List<Iso9660.Entry> entries=List.of();
    private final List<CheckBox> checks=new ArrayList<>();
    private LinearLayout list;
    private TextView status,summary;
    private ProgressBar progress;
    private Button choose,write,cancel;

    @Override public void onCreate(Bundle b){super.onCreate(b);buildUi();}
    private void buildUi(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(24),dp(20),dp(20));root.setBackgroundColor(Color.rgb(245,245,250));
        TextView title=text("Cutscene Manager PS2",28,true);root.addView(title);
        root.addView(text("Preview PSS, pilih yang tidak penting, lalu ganti dengan layar hitam 1 detik.",14,false),margins(0,dp(5),0,dp(18)));
        choose=button("Pilih ISO PS2");choose.setOnClickListener(v->startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE),PICK));root.addView(choose);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);root.addView(progress,margins(0,dp(15),0,dp(8)));
        status=text("Belum ada ISO dipilih",16,true);root.addView(status);summary=text("ISO asli tidak akan diubah.",13,false);root.addView(summary,margins(0,dp(4),0,dp(10)));
        ScrollView scroll=new ScrollView(this);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        write=button("Buat ISO baru");write.setEnabled(false);write.setOnClickListener(v->createOutput());root.addView(write,margins(0,dp(10),0,0));
        cancel=button("Batalkan");cancel.setVisibility(View.GONE);cancel.setOnClickListener(v->{cancelled.set(true);cancel.setEnabled(false);status.setText("Membatalkan…");});root.addView(cancel);
        setContentView(root);
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;if(request==PICK){isoUri=data.getData();scan();}else if(request==CREATE)rewrite(data.getData());}
    private void scan(){busy(true);status.setText("Memindai struktur ISO…");worker.execute(()->{try{List<Iso9660.Entry> found=Iso9660.scan(getContentResolver(),isoUri);runOnUiThread(()->showEntries(found));}catch(Exception e){fail(e);}});}
    private void showEntries(List<Iso9660.Entry> found){entries=found;checks.clear();list.removeAllViews();long total=0;
        for(int i=0;i<found.size();i++){Iso9660.Entry e=found.get(i);total+=e.size();LinearLayout row=new LinearLayout(this);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            CheckBox c=new CheckBox(this);c.setText(e.path()+"\n"+size(e.size()));c.setTag(i);c.setOnCheckedChangeListener((b,x)->updateSelection());checks.add(c);row.addView(c,new LinearLayout.LayoutParams(0,-2,1));
            Button preview=button("Preview");preview.setOnClickListener(v->preview(e));row.addView(preview);list.addView(row);}
        busy(false);status.setText(found.isEmpty()?"Cutscene PSS tidak ditemukan":found.size()+" cutscene ditemukan");summary.setText(found.isEmpty()?"Game mungkin menyimpan video dalam arsip internal atau format lain.":"Total video: "+size(total)+" · pilih cutscene yang ingin diganti.");
    }
    private void updateSelection(){long bytes=0;int count=0;for(CheckBox c:checks)if(c.isChecked()){Iso9660.Entry e=entries.get((int)c.getTag());bytes+=Math.max(0,e.size()-18432);count++;}write.setEnabled(count>0);summary.setText(count+" dipilih · potensi hemat setelah kompresi sekitar "+size(bytes));}
    private void preview(Iso9660.Entry e){status.setText("Menyiapkan preview…");worker.execute(()->{try{File f=new File(getCacheDir(),"preview.mpg");try(FileOutputStream out=new FileOutputStream(f)){Iso9660.extract(getContentResolver(),isoUri,e,out);}runOnUiThread(()->showVideo(f,e.path()));}catch(Exception ex){fail(ex);}});}
    private void showVideo(File f,String name){VideoView video=new VideoView(this);MediaController controls=new MediaController(this);controls.setAnchorView(video);video.setMediaController(controls);video.setVideoPath(f.getAbsolutePath());AlertDialog d=new AlertDialog.Builder(this).setTitle(name).setView(video).setPositiveButton("Tutup",null).create();video.setOnErrorListener((player,what,extra)->{status.setText("Preview tidak didukung decoder perangkat");summary.setText("Cutscene tetap dapat diganti, tetapi format PSS game ini tidak bisa diputar oleh Android.");return true;});d.setOnShowListener(x->video.start());d.setOnDismissListener(x->{video.stopPlayback();f.delete();});d.show();status.setText("Preview siap");}
    private void createOutput(){startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/octet-stream").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,"PS2-cutscene-removed.iso"),CREATE);}
    private void rewrite(Uri output){List<Iso9660.Entry> selected=new ArrayList<>();for(CheckBox c:checks)if(c.isChecked())selected.add(entries.get((int)c.getTag()));busy(true);cancelled.set(false);status.setText("Menyalin ISO…");worker.execute(()->{try{byte[] black=readRaw();Iso9660.rewrite(getContentResolver(),isoUri,output,selected,black,cancelled,(done,total)->runOnUiThread(()->{progress.setProgress((int)(done*1000/total));status.setText("Membuat ISO baru… "+done*100/total+"%");}));runOnUiThread(()->{busy(false);progress.setProgress(1000);status.setText("ISO baru selesai");summary.setText(selected.size()+" cutscene diganti layar hitam. Sekarang ISO dapat dikompres ke CHD.");});}catch(Exception e){try{getContentResolver().delete(output,null,null);}catch(Exception ignored){}fail(e);}});}
    private byte[] readRaw()throws Exception{try(InputStream in=getResources().openRawResource(R.raw.black_ps2);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    private void fail(Exception e){runOnUiThread(()->{busy(false);progress.setProgress(0);status.setText(e instanceof CsoCompressor.CancelledException?"Dibatalkan":"Gagal");summary.setText(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());});}
    private boolean hasSelection(){for(CheckBox c:checks)if(c.isChecked())return true;return false;}
    private void busy(boolean on){choose.setEnabled(!on);write.setEnabled(!on&&hasSelection());cancel.setVisibility(on?View.VISIBLE:View.GONE);cancel.setEnabled(on);if(on)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);}
    private TextView text(String s,int n,boolean bold){TextView v=new TextView(this);v.setText(s);v.setTextSize(n);v.setTextColor(Color.rgb(24,24,32));if(bold)v.setTypeface(null,1);return v;}private Button button(String s){Button b=new Button(this);b.setText(s);b.setAllCaps(false);return b;}private LinearLayout.LayoutParams margins(int l,int t,int r,int b){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(l,t,r,b);return p;}private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}private static String size(long n){double v=n;String[]u={"B","KB","MB","GB"};int i=0;while(v>=1024&&i<3){v/=1024;i++;}return String.format(Locale.getDefault(),"%.1f %s",v,u[i]);}
}
