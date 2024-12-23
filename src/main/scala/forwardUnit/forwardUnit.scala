package pipeline.forwrdUnit
/**
  * 'forwardUnit' keeps the results of executed, but
  * not yet retired instructions and forwards them to
  * the exec unit when a new instruction is issued
  * 
  * This unit will takeover some of the responsibilities
  * of the RoB. The RoB will be removed from the pipeline.
  * 
  * 'resultsBuffer' will store the results of all
  * the transient instructions. The execution units
  * will write to the resultsBuffer using the fwdAddr
  * assigned to the instruction.
  * 
  * All instructions in the pipeline has an unique
  * fwdAddr which assigned when the decode issues the
  * instruction to exec.
  * 
  * The fence was triggered from RoB in the previous
  * implementation, it will be done directly via 
  * memAccess w/o any need for forward unit to contribute 
  */
import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._


class forwardUnit extends Module {
  // defining ports
  // This port will most likely will have to be
  // removed
  val carryOutFence = IO(new composableInterface)

  // assigns a fwdAddr to the instruction
  // 'robAllocate' will have to be renamed
  val fromDecode = IO(new robAllocate)

  // Dedicated port to take results of transient
  // instruction for non memory access instrunctions
  val fromExec = IO(new pullExecResultToRob)

  // Dedicated port to take results of transient
  // instruction for memory access instrunctions
  val fromMem = IO(new pullMemResultToRob)

  // When an instruction retires to decode, we have 
  // to free up an entry in resultsBuffer
  // 'commitInstruction' has to be renamed
  // we are just reusing the interface name
  val freeEntry = IO(new commitInstruction)

  // logic starts here
  val resultsBuffer = RegInit(VecInit(Seq.fill(1 << fwdAddrWidth)((new Bundle{
    val valid   = Bool()
    val result  = UInt(XLEN.W)
  }).Lit(
    _.valid -> true.B
  ))))
  
  // There are two write ports to resultsBuffer
  // Hence, the two write ports are always ready
  // (resource utilization is neglegible)
  // We will most likely won't be using the fromMem
  // interface anyway (lets see)
  fromExec.ready := true.B
  fromMem.ready := true.B

  // Interface that frees up instructions should
  // always be ready (resource utilization is neglegible)
  freeEntry.ready := true.B

  val buffersAllocated = RegInit(VecInit.fill(1 << fwdAddrWidth)(false.B))
  when (fromDecode.fired) { 
    buffersAllocated(fromDecode.robAddr) := true.B
    resultsBuffer(fromDecode.robAddr).valid := false.B // Making sure stale data is not forwarded
  }
  when (freeEntry.fired) { 
    // freeing the buffer for a new instruction
    buffersAllocated(freeEntry.fwdAddr) := false.B 
  }
  fromDecode.ready := !buffersAllocated.reduce(_ && _) // are all buffers allocated?
  // TODO: 'robAddr' needs to renamed everywhere
  // highest index with free buffer has highest priority
  fromDecode.robAddr := (0 until (1 << fwdAddrWidth)).foldLeft(0.U) { case(prev_index, curr_index) => Mux(buffersAllocated(curr_index), prev_index, curr_index.U)}
  
  // Forwarding data to exec
  fromDecode.fwdrs1.value := resultsBuffer(fromDecode.fwdrs1.robAddr).result
  fromDecode.fwdrs1.valid := resultsBuffer(fromDecode.fwdrs1.robAddr).valid
  fromDecode.fwdrs2.value := resultsBuffer(fromDecode.fwdrs2.robAddr).result
  fromDecode.fwdrs2.valid := resultsBuffer(fromDecode.fwdrs2.robAddr).valid

  // Writing to resultsBuffer from execution ports
  when(fromExec.fired) {
    resultsBuffer(fromExec.robAddr).result := fromExec.execResult
    resultsBuffer(fromExec.robAddr).valid := true.B
  }
  when(fromMem.fired) {
    resultsBuffer(fromMem.robAddr).result := fromMem.writeBackData
    resultsBuffer(fromMem.robAddr).valid := true.B
  }

  // Hardwiring outputs that are soon to be removed
  carryOutFence.ready := false.B
  freeEntry.robAddr := 0.U
  freeEntry.rdAddr := 0.U
  freeEntry.opcode := 0.U
  freeEntry.writeBackData := 0.U
  freeEntry.execptionOccured := false.B
  freeEntry.mcause := 0.U
  freeEntry.mepc := 0.U
}

object forwardUnit extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new forwardUnit)
}
