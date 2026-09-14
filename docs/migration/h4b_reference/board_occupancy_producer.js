'use strict';
/** H4-B authoritative extracted guard reference from runtime/board_occupancy_producer.js. */
var P65G_BANNER={x:850,y:830,w:1450,h:15,edgeMin:100.0,coverageMin:0.70,pixelEdgeMin:30.0};
function detectBoardBanner(mat){
  var cv=org.opencv,Core=cv.core.Core,I=cv.imgproc.Imgproc;
  var sub=null,gray=null,gy=null,absGy=null,rows=null,peakRow=null,mask=null;
  try{
    if(!mat||mat.empty())return {evaluated:false,detected:false,reason:'MAT_EMPTY'};
    if(mat.cols()!==3120||mat.rows()!==1440)return {evaluated:false,detected:false,reason:'FRAME_DIMS_'+mat.cols()+'x'+mat.rows()};
    sub=mat.submat(P65G_BANNER.y,P65G_BANNER.y+P65G_BANNER.h,P65G_BANNER.x,P65G_BANNER.x+P65G_BANNER.w);
    gray=new cv.core.Mat();
    if(sub.channels()===4)I.cvtColor(sub,gray,I.COLOR_RGBA2GRAY);
    else if(sub.channels()===3)I.cvtColor(sub,gray,I.COLOR_BGR2GRAY);
    else sub.convertTo(gray,cv.core.CvType.CV_8U);
    gy=new cv.core.Mat(); I.Sobel(gray,gy,cv.core.CvType.CV_32F,0,1,3);
    absGy=new cv.core.Mat(); Core.convertScaleAbs(gy,absGy);
    rows=new cv.core.Mat(); Core.reduce(absGy,rows,1,Core.REDUCE_AVG,cv.core.CvType.CV_32F);
    var mm=Core.minMaxLoc(rows),peak=mm.maxVal*1,rowIdx=Math.round(mm.maxLoc.y*1);
    peakRow=absGy.row(rowIdx); mask=new cv.core.Mat();
    I.threshold(peakRow,mask,P65G_BANNER.pixelEdgeMin,255,I.THRESH_BINARY);
    var coverage=Core.countNonZero(mask)/P65G_BANNER.w;
    var detected=(peak>=P65G_BANNER.edgeMin&&coverage>=P65G_BANNER.coverageMin);
    return {evaluated:true,detected:detected,reason:detected?'BOARD_BANNER_LIKE':'CLEAN_FOR_BANNER',peakMeanAbsGy:peak,coverage:coverage,peakY:P65G_BANNER.y+rowIdx};
  }catch(e){return {evaluated:false,detected:false,reason:'GUARD_EXCEPTION:'+String(e));}
}
var P65H_DAMAGE_PANEL={x:2760,y:150,w:360,h:900,meanMax:120.0,stdMax:45.0};
function detectDamagePanel(mat){
  var cv=org.opencv,Core=cv.core.Core,I=cv.imgproc.Imgproc;
  var sub=null,gray=null,mean=null,sd=null;
  try{
    if(!mat||mat.empty())return {evaluated:false,detected:false,reason:'MAT_EMPTY'};
    if(mat.cols()!==3120||mat.rows()!==1440)return {evaluated:false,detected:false,reason:'FRAME_DIMS_'+mat.cols()+'x'+mat.rows()};
    sub=mat.submat(P65H_DAMAGE_PANEL.y,P65H_DAMAGE_PANEL.y+P65H_DAMAGE_PANEL.h,P65H_DAMAGE_PANEL.x,P65H_DAMAGE_PANEL.x+P65H_DAMAGE_PANEL.w);
    gray=new cv.core.Mat();
    if(sub.channels()===4)I.cvtColor(sub,gray,I.COLOR_RGBA2GRAY);
    else if(sub.channels()===3)I.cvtColor(sub,gray,I.COLOR_BGR2GRAY);
    else sub.copyTo(gray);
    mean=new cv.core.MatOfDouble(); sd=new cv.core.MatOfDouble(); Core.meanStdDev(gray,mean,sd);
    var m=mean.toArray()[0]*1,s=sd.toArray()[0]*1;
    var detected=(m<P65H_DAMAGE_PANEL.meanMax&&s<P65H_DAMAGE_PANEL.stdMax);
    return {evaluated:true,detected:detected,reason:detected?'DAMAGE_PANEL_LIKE':'CLEAN_FOR_DAMAGE_PANEL',mean:m,std:s};
  }catch(e){return {evaluated:false,detected:false,reason:'GUARD_EXCEPTION:'+String(e));}
}
