

/**
  * * * * * IMPORTANT * * * * * 
  * There is only one maintainer of this file. Only the maintainer is only allowed
  * to make changes to this file in the *main* branch.
  * 
  * Maintainer: Kaveesha Yalegama
  */

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

/**
  * Contains the class definitions of ports connecting the modules of
  * the pipeline. There are two types of interfaces (1) Decoupled interface
  * and (2) Composable interface.
  * 
  * Decoupled interface loose their meaning when communication needs to happen
  * between more than 2 modules or the data transfer is bidirectional. All or
  * almost all of the interfaces will be composable interfaces in the future.
  * Eg of the use of a composable interface than a decoupled interface
  * 1. When issuing an instruction communication needs to happen between three
  * modules(decode, exec and rob)
  * 2. Between decode and fetch, decode needs to communicate to the fetch the
  * pc of the instruction its expenting and recieve the instruction sent by the
  * fetch unit(bi directional transaction)
  * 
  * Even if the designer is 100% certain that a interface will always be ready
  * for transaction, use a composable interface or a decoupled interface. The 
  * ready signal can be set to be always asserted. Always asserted ready cannot
  * be assumed by any other module communicating with the module. 
  */

/**
  * Composable Interface: Used when connecting composable modules as explained
  * in: https://ieeexplore.ieee.org/document/8686204
  * 
  * ready - The interface of module is ready to fire.
  * fired - All the other interfaces involved in the rule is ready and the rule
  *   has fired.
  */
class composableInterface extends Bundle {
  val ready = Output(Bool())
  val fired = Input(Bool())
}

/**
  * Rule - pass_instruction_to_decode
  * 
  * issueInstrFrmFetch - Get new instruction from fetch
  * 
  * recivInstrFrmFetch - Get new instruction to decode
  * 
  * Only fired when issuing pc and expected pc are the same. If decode is not
  * aware of the next pc in the control flow. Then it will accept the presented pc
  * from fetch when both interfaces are ready.
  * 
  * The decode unit communicates that its aware of the next instruction in the 
  * control flow through *expected.valid and *expected.pc will indicate the pc
  * of the next instruction it is expecting.
  * 
  * Firing
  * "issueInstrFrmFetch.ready && recivInstrFrmFetch.ready && \
  * (!recivInstrFrmFetch.expected.valid || (recivInstrFrmFetch.expected.pc === issueInstrFrmFetch.expected.pc))"
  */

/**
  * Rule - pass_instruction_to_decode
  * 
  * fetch-unit port
  */
class issueInstrFrmFetch extends composableInterface {
  val pc          = Output(UInt(64.W))
  val instruction = Output(UInt(32.W))
  val expected    = Input(new Bundle {
    val valid = Bool()
    val pc    = UInt(64.W)
  })
}

/**
  * Rule - pass_instruction_to_decode
  * 
  * decode unit port
  */
class recivInstrFrmFetch extends composableInterface {
  val pc          = Input(UInt(64.W))
  val instruction = Input(UInt(32.W))
  val expected    = Output(new Bundle {
    val valid = Bool()
    val pc    = UInt(64.W)
  })
} 

/**
  * Rule - passing_branch_results
  * 
  * branchResFrmDecode - send branch results after decoding and execution
  * 
  * branchResToFetch - get branch results
  * 
  * Firing branchResFrmDecode.ready && branchResToFetch.ready
  */

/**
  * Rule - passing_branch_results
  * 
  * decode unit port
  */
class branchResFrmDecode extends composableInterface {
  val isBranch      = Output(Bool()) // this signal was added to account for dynamic changes in instruction memory(This one might not be needed)
  val branchTaken   = Output(Bool())
  val pc            = Output(UInt(32.W)) // pc of the branched instruction
  val pcAfterBrnach = Output(UInt(32.W)) // pc of the next instruction after branch
}

/**
  * Rule - passing_branch_results
  * 
  * fetch unit port
  */
class branchResToFetch extends composableInterface {
  val isBranch      = Input(Bool()) // this signal was added to account for dynamic changes in instruction memory(This one might not be needed)
  val branchTaken   = Input(Bool())
  val pc            = Input(UInt(32.W)) // pc of the branched instruction
  val pcAfterBrnach = Input(UInt(32.W)) // pc of the next instruction after branch
}


//======================DECOUPLED INTERFACES============================
/**
  * DecoupledIO function in chisel builds a decoupled interface from a bundle
  * given as the input.
  */  

class MemoryIssuePort extends Bundle {
    val instruction = UInt(32.W)
    val nextInstPtr = UInt(64.W)
    val aluResult   = UInt(64.W)
}

class AluIssuePort extends Bundle {
    val instruction = UInt(32.W)
    val nextInstPtr = UInt(64.W)
    val aluResult   = UInt(64.W)
    val rs2         = UInt(64.W)
}

class DecodeIssuePort extends Bundle {
    val instruction = UInt(32.W)
    val pc          = UInt(64.W)
    val rs1         = UInt(64.W)
    val rs2         = UInt(64.W)
    val immediate   = UInt(64.W)
}

class FifoIO[T <: Data ](private val gen: T) extends Bundle {
  val enq = Flipped(new DecoupledIO(gen))
  val deq = new DecoupledIO (gen)
}