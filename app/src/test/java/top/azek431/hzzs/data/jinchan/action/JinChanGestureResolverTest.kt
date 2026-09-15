package top.azek431.hzzs.data.jinchan.action

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.io.path.extension
import top.azek431.hzzs.data.jinchan.frame.JinChanFrameSessionId
import top.azek431.hzzs.data.jinchan.ledger.UnitLocation
import top.azek431.hzzs.domain.automation.GestureSpec

class JinChanGestureResolverTest {
    @Test fun noProfileBlocks() { assertBlocked(JinChanGestureBlockedReason.PROFILE_UNAVAILABLE, input(buy(0), null)) }
    @Test fun buyRejectsInvalidAndMissingSlotButCalibratedSlotClicks() {
        assertBlocked(JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION, input(buy(5), profile())); assertBlocked(JinChanGestureBlockedReason.TARGET_UNCALIBRATED, input(buy(0), profile()))
        val p=point(.2f,.3f); val r=resolved(input(buy(2),profile(shopSlots=slots(5,2 to p)))); assertEquals(GestureSpec(p.x,p.y),r.gesture); assertNull(r.gesture.endX); assertNull(r.gesture.endY); assertEquals("test-profile",r.provenance.profileId)
    }
    @Test fun refreshAndBuyXpRequireTheirOwnCalibration() {
        assertBlocked(JinChanGestureBlockedReason.TARGET_UNCALIBRATED,input(approved(JinChanActionIntent.RefreshShop),profile())); assertBlocked(JinChanGestureBlockedReason.TARGET_UNCALIBRATED,input(approved(JinChanActionIntent.BuyXp),profile()))
        val r=point(.1f,.9f); val x=point(.8f,.7f); assertEquals(GestureSpec(r.x,r.y),resolved(input(approved(JinChanActionIntent.RefreshShop),profile(refreshShop=r))).gesture); assertEquals(GestureSpec(x.x,x.y),resolved(input(approved(JinChanActionIntent.BuyXp),profile(buyXp=x))).gesture)
    }
    @Test fun moveRequiresValidKnownAndCalibratedSource() {
        val d=UnitLocation.Bench(0); val a=move(d); val p=profile(benchSlots=slots(9,0 to point(.3f,.4f))); assertBlocked(JinChanGestureBlockedReason.SOURCE_LOCATION_UNAVAILABLE,input(a,p)); assertBlocked(JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION,input(a,p,UnitLocation.Unknown)); assertBlocked(JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION,input(a,p,UnitLocation.None)); assertBlocked(JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION,input(a,p,UnitLocation.Board(0,1))); assertBlocked(JinChanGestureBlockedReason.SOURCE_LOCATION_UNAVAILABLE,input(a,p,UnitLocation.Board(1,1)))
    }
    @Test fun moveRequiresCalibratedDestination() { val s=UnitLocation.Board(1,1); val p=profile(boardCells=mapOf(s to point(.1f,.2f))); assertBlocked(JinChanGestureBlockedReason.DESTINATION_UNCALIBRATED,input(move(UnitLocation.Board(2,2)),p,s)); assertBlocked(JinChanGestureBlockedReason.INVALID_LOGICAL_LOCATION,input(move(UnitLocation.Board(5,1)),p,s)) }
    @Test fun calibratedBoardAndBenchMovesProduceDeterministicDrag() { val b=UnitLocation.Board(1,1); val n=UnitLocation.Bench(3); val bp=point(.2f,.4f); val np=point(.7f,.8f); val p=profile(boardCells=mapOf(b to bp),benchSlots=slots(9,3 to np)); val a=input(move(n),p,b); assertEquals(drag(bp,np),resolved(a).gesture); assertEquals(JinChanGestureResolver.resolve(a),JinChanGestureResolver.resolve(a)) }
    @Test fun sellFailsClosedWithoutTargetAndSyntheticTargetProducesDrag() { val s=UnitLocation.Bench(1); val sp=point(.4f,.8f); val p=profile(benchSlots=slots(9,1 to sp)); val sell=input(approved(JinChanActionIntent.SellUnit(9)),p,s); assertBlocked(JinChanGestureBlockedReason.SELL_TARGET_UNCALIBRATED,sell); val t=point(.9f,.1f); assertEquals(drag(sp,t),resolved(sell.copy(profile=p.copy(sellTarget=t))).gesture) }
    @Test fun normalizedPointRejectsEveryNonFiniteOrOutOfRangeCoordinate() { listOf(Float.NaN,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,-.01f,1.01f).forEach { v->assertThrows(IllegalArgumentException::class.java){JinChanNormalizedPoint(v,.5f)};assertThrows(IllegalArgumentException::class.java){JinChanNormalizedPoint(.5f,v)} }; assertEquals(point(0f,1f),JinChanNormalizedPoint(0f,1f)) }
    @Test fun profileRequiresExactCoordinateTableSizes() { assertThrows(IllegalArgumentException::class.java){profile(shopSlots=List(4){null})}; assertThrows(IllegalArgumentException::class.java){profile(benchSlots=List(8){null})} }
    @Test fun productionResolverHasNoExecutionOrTransportReferences() { val root=Path.of("src/main/java/top/azek431/hzzs/data/jinchan/action").takeIf(Files::exists)?:Path.of("app/src/main/java/top/azek431/hzzs/data/jinchan/action"); val forbidden=listOf("AutomationAction","GestureArbiter","GestureDispatcher","GestureDispatcherFactory","HzzsAccessibilityService","dispatchGesture","android.accessibilityservice","Shizuku","Root input","shell input","capture","OCR","CV"); val src=Files.walk(root).use{p->p.filter{Files.isRegularFile(it)&&it.extension=="kt"&&it.fileName.toString().contains("GestureResolver")}.map(Files::readString).toList()}; assertTrue(src.isNotEmpty()); forbidden.forEach{t->assertEquals("forbidden production token: $t",false,src.any{t in it})} }
    private fun point(x:Float,y:Float)=JinChanNormalizedPoint(x,y)
    private fun profile(shopSlots:List<JinChanNormalizedPoint?> = List(5){null},boardCells:Map<UnitLocation.Board,JinChanNormalizedPoint?> = emptyMap(),benchSlots:List<JinChanNormalizedPoint?> = List(9){null},refreshShop:JinChanNormalizedPoint?=null,buyXp:JinChanNormalizedPoint?=null)=JinChanCoordinateProfile("test-profile",shopSlots,boardCells,benchSlots,refreshShop,buyXp)
    private fun slots(size:Int,c:Pair<Int,JinChanNormalizedPoint>):List<JinChanNormalizedPoint?> = List(size){i->c.second.takeIf{i==c.first}}
    private fun buy(slot:Int)=approved(JinChanActionIntent.BuyShopSlot(slot)); private fun move(d:UnitLocation)=approved(JinChanActionIntent.MoveUnit(9,d))
    private fun approved(i:JinChanActionIntent)=ApprovedJinChanAction(i,JinChanActionApprovalProvenance(JinChanFrameSessionId(1),2,3,JinChanActionSafetyGate.JINCHAN_PACKAGE))
    private fun input(a:ApprovedJinChanAction,p:JinChanCoordinateProfile?,s:UnitLocation?=null)=JinChanGestureResolverInput(a,p,s)
    private fun drag(a:JinChanNormalizedPoint,b:JinChanNormalizedPoint)=GestureSpec(a.x,a.y,b.x,b.y,JinChanGestureResolver.DRAG_DURATION_MS)
    private fun resolved(i:JinChanGestureResolverInput):JinChanGestureResolveResult.Resolved { val r=JinChanGestureResolver.resolve(i); assertTrue(r is JinChanGestureResolveResult.Resolved); return r as JinChanGestureResolveResult.Resolved }
    private fun assertBlocked(reason:JinChanGestureBlockedReason,i:JinChanGestureResolverInput){val r=JinChanGestureResolver.resolve(i);assertTrue(r is JinChanGestureResolveResult.Blocked);assertEquals(reason,(r as JinChanGestureResolveResult.Blocked).reason);assertEquals(i.action,r.action)}
}
