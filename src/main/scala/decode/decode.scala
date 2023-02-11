package pipeline.decode

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._

/**
  * Functionality - Must communicate the pc of the first instruction to execute
  * through from Fetch.
  * 
  * Details about the IO can be found on common/ports.scala
  *
  */
class decode extends Module {
  /**
    * Inputs and Outputs of the module
    */

  // receives instructions from fetch
  // and communicates the pc of the expected instruction
  val fromFetch = IO(new recivInstrFrmFetch)

  // sends results of branched to fetch unit
  val branchRes = IO(new branchResFrmDecode)

  // sends the decoded instruction to the next stage of the pipeline
  val toExec = IO(DecoupledIO(new DecodeIssuePort))

  /**
    * Internal of the module goes here
    */
}