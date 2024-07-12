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
  // instruction for non memory access instrunctions
  val fromMem = IO(new pullMemResultToRob)

  // When an instruction retires to decode, we have 
  // to free up an entry in resultsBuffer
  // 'commitInstruction' has to be renamed
  val freeEntry = IO(new commitInstruction)

  // logic starts here

}

object forwardUnit extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new forwardUnit)
}
