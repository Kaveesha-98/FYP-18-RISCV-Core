package pipeline.ports

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
import pipeline.configuration.coreConfiguration._

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
  val pc            = Output(UInt(64.W)) // pc of the branched instruction
  val pcAfterBrnach = Output(UInt(64.W)) // pc of the next instruction after branch
  val isRas = Output(Bool())
  val rasAction = Output(UInt(2.W))
}

object ras_constants{
  // ras actions
  val pop = 0
  val push = 1
  val popThenPush = 2
}

/**
  * Rule - passing_branch_results
  * 
  * fetch unit port
  */
class branchResToFetch extends composableInterface {
  val isBranch      = Input(Bool()) // this signal was added to account for dynamic changes in instruction memory(This one might not be needed)
  val branchTaken   = Input(Bool())
  val pc            = Input(UInt(64.W)) // pc of the branched instruction
  val pcAfterBrnach = Input(UInt(64.W)) // pc of the next instruction after branch
  val isRas = Input(Bool())
  val rasAction = Input(UInt(2.W))
}

/*******************************************************************/
/**
  * Rule - retire_instruction
  * retireInsFromRob - Retires instruction, fired from Rob to Decode
  * pullInsFromRob - Pulls instruction along with its result to decode from Rob
  *   to retire instruction
  * 
  * If an exceptionOccured, then exception handling begins after that.
  * 
  * If an interrupt was in the RoB at this point, the interrput is given
  * prority over the exception (mcause will represent interrupt)
  */

/**
  * Rule - retire_instruction
  * 
  * decode unit port
  *   for each instruction issued on to pipeline, this rule will fire
  *   robAddr is used to solve WAW dependencies
  */  
class pullCommitFrmRob extends composableInterface {
  val robAddr       = Input(UInt(robAddrWidth.W))
  val rdAddr        = Input(UInt(5.W))
  val opcode = Input(UInt(7.W))
  val writeBackData = Input(UInt(64.W)) // mtval when exceptionOccured
  //Additional wires when exception handling is added
  val execptionOccured  = Input(Bool())
  val mcause            = Input(UInt(64.W))
  val mepc              = Input(UInt(64.W))
} 

/**
  * Rule - retire instruction
  *
  * rob port
  * mcause will be defined by exec {4, 5, 6, 7, 13, 15}
  */
class commitInstruction extends composableInterface {
  val robAddr       = Output(UInt(robAddrWidth.W))
  val rdAddr        = Output(UInt(5.W))
  val opcode = Output(UInt(7.W))
  val writeBackData = Output(UInt(64.W)) // mtval when exceptionOccured
  // Additional wires when exception handling is added
  val execptionOccured  = Output(Bool())
  val mcause            = Output(UInt(64.W))
  val mepc              = Output(UInt(64.W))
} 
/*******************************************************************/

/*******************************************************************/
/**
  * Rule - issue_instruction_to_pipeline
  * 
  * Pushes instruction to pipeline, instruction is allocated in rob
  *
  * Once interrupt handling is introduced, once intrrupts are detected
  * and if pipeline is not free at the time, interrupt is introduced as
  * an instruction to the end of the pipeline. 
  * 
  * The instruction format of interrupt is all zeros.
  */

/**
  * Rule - issue_instruction_to_pipeline
  *
  * decode interface
  */
class pushInsToPipeline extends composableInterface {
  // IMPORTANT - value of x0 should never be issued with *.fromRob asserted
  val src1        = Output(new Bundle {
    val fromRob = Bool()
    val data = UInt(64.W)
    val robAddr = UInt(robAddrWidth.W)  
  })               // {jal, jalr, auipc - pc}, {loads, stores, rops*, iops*, conditionalBranches - rs1}
  val src2        = Output(src1.cloneType)        // {jalr, jal - 4.U}, {loads, stores, iops*, auipc - immediate}, {rops* - rs2}
  val writeData   = Output(src1.cloneType)
  val instruction = Output(UInt(32.W))
  val pc          = Output(UInt(64.W))
  val robAddr     = Input(UInt(robAddrWidth.W))   // allocated address in rob
}

/**
  * Rule - issue_instruction_to_pipeline
  * 
  * rob interface
  */
class robAllocate extends composableInterface {
  val instOpcode = Input(UInt(7.W))
  val robAddr = Output(UInt(robAddrWidth.W))
  val rd = Input(UInt(5.W))
  val fwdrs1 = new Bundle {
    val valid = Output(Bool())
    val value = Output(UInt(64.W))
    val robAddr = Input(UInt(robAddrWidth.W))
  }
  val fwdrs2 = new Bundle {
    val valid = Output(Bool())
    val value = Output(UInt(64.W))
    val robAddr = Input(UInt(robAddrWidth.W))
  }
  //Wire added to handle exceptions
  val pc = Input(UInt(32.W)) // to provide mepc when exception are reported
}

/**
  * Rule - issue_instruction_to_pipeline
  * 
  * exec interface
  */
class pullToPipeline extends composableInterface {
  val robAddr     = Input(UInt(robAddrWidth.W))
  val src1        = Input(UInt(64.W))
  val src2        = Input(UInt(64.W))
  val writeData   = Input(UInt(64.W))
  val instruction = Input(UInt(32.W))
}
/*******************************************************************/

/*******************************************************************/
/**
  * Rule - issue_request_to_memory
  *
  * pushes a request to memory, at the current RoB is not involved in 
  * commiting changes to memory, this should not be a problem for in-order
  * 
  * Once Rob can be made involved by changing this Rule and retire_instruction
  * rule, without changing any of the interfaces
  */

/**
  * Rule - issue_request_to_memory
  * 
  * exec interface
  */
class pushToMemory extends composableInterface {
  val robAddr     = Output(UInt(robAddrWidth.W))
  val memAddress  = Output(UInt(64.W))
  val writeData   = Output(UInt(64.W)) // right justified as in rs2
  val instruction = Output(UInt(32.W))
}

/**
  * Rule - issue_request_to_memory
  * 
  * exec interface
  */
class pullPipelineMemReq extends composableInterface {
val address  = Input(UInt(64.W))
val writeData = Input(UInt(64.W)) // not aligned
val robAddr = Input(UInt(robAddrWidth.W))
val instruction = Input(UInt(32.W))
}
/*******************************************************************/

/*******************************************************************/
/**
  * Rule - push_exec_results_to_rob
  *
  * Push results of non-memory requests to pipeline. 
  * 
  * When exception handling is added, exec is responsible to look for 
  * exceptions. Once detected(happens when memory address is calculated)
  * the address that created the exception is returned to RoB and stops
  * executing until exception handling is started
  */

/**
  * Rule - push_exec_results_to_rob
  *
  * exec interface
  */
class pushExecResultToRob extends composableInterface {
  val robAddr     = Output(UInt(robAddrWidth.W))
  val execResult  = Output(UInt(64.W)) // mtval when exceptionOccured
  // Additional wires when exception handling is added
  val execptionOccured  = Output(Bool())
  val mcause            = Output(UInt(64.W))
  //val mepc              = Output(UInt(64.W))
}

/**
  * Rule - push_exec_results_to_rob
  *
  * rob interface
  */
class pullExecResultToRob extends composableInterface {
  val robAddr     = Input(UInt(robAddrWidth.W))
  val execResult  = Input(UInt(64.W)) // mtval when exceptionOccured
  // Additional wires when exception handling is added
  val execeptionOccured  = Input(Bool())
  val mcause            = Input(UInt(64.W))
}
/*******************************************************************/

/*******************************************************************/
/**
  * Rule - push_mem_results_to_rob
  *
  * Push results of non-memory requests to pipeline. 
  * 
  * When exception handling is added, exec is responsible to look for 
  * exceptions. Once detected(happens when memory address is calculated)
  * the address that created the exception is returned to RoB and stops
  * executing until exception handling is started
  */

/**
  * Rule - push_mem_results_to_rob
  *
  * memAccess interface
  */
class pushMemResultToRob extends composableInterface {
  val robAddr     = Output(UInt(robAddrWidth.W))
  val writeBackData  = Output(UInt(64.W))
}

/**
  * Rule - push_exec_results_to_rob
  *
  * rob interface
  */
class pullMemResultToRob extends composableInterface {
  val robAddr     = Input(UInt(robAddrWidth.W))
  val writeBackData  = Input(UInt(64.W))
}
/*******************************************************************/

//**** interfaces and rules relating to fence are not yet added

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