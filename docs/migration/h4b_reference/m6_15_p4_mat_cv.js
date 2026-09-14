'use strict';
/** Authoritative H4-B reference copied verbatim from current production migration package. */
var cv = null;
function O(){ if(!cv) cv=org.opencv; return cv; }
function ms(){ return java.lang.System.nanoTime()/1e6; }
function computeMats(anchor){
  var o=O(), C=o.core.CvType, I=o.imgproc.Imgproc, K=o.core.Core;
  if(anchor.rows()!==80||anchor.cols()!==110) throw new Error('P4 host requires 110x80 anchor');
  var t, d={colorConvert:0,gray:0,sobelGxGy:0,magnitude:0,laplacian:0};
  var bgr=anchor,tmp=null;
  t=ms();
  if(anchor.channels()===4){ tmp=new o.core.Mat(); I.cvtColor(anchor,tmp,I.COLOR_BGRA2BGR); bgr=tmp; }
  d.colorConvert=ms()-t;
  var g=new o.core.Mat(),g32=new o.core.Mat(),gx=new o.core.Mat(),gy=new o.core.Mat(),mag=new o.core.Mat(),lap=new o.core.Mat();
  try {
    t=ms(); I.cvtColor(bgr,g,I.COLOR_BGR2GRAY); g.convertTo(g32,C.CV_32F); d.gray=ms()-t;
    if(tmp){ try{tmp.release();}catch(e0){} tmp=null; }
    t=ms(); I.Sobel(g32,gx,C.CV_32F,1,0,3); I.Sobel(g32,gy,C.CV_32F,0,1,3); d.sobelGxGy=ms()-t;
    t=ms(); K.magnitude(gx,gy,mag); d.magnitude=ms()-t;
    t=ms(); I.Laplacian(g32,lap,C.CV_32F,3); d.laplacian=ms()-t;
    g.release(); g=null;
    return {mats:{gray:g32,gx:gx,gy:gy,mag:mag,lap:lap},timing:d,release:function(){var a=[g32,gx,gy,mag,lap],i;for(i=0;i<a.length;i++){try{if(a[i])a[i].release();}catch(e1){}}}};
  } catch(e){
    try{if(tmp)tmp.release();}catch(e2){}
    var z=[g,g32,gx,gy,mag,lap],j;for(j=0;j<z.length;j++){try{if(z[j])z[j].release();}catch(e3){}}
    throw e;
  }
}
module.exports={computeMats:computeMats};
