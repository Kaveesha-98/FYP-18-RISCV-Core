package fetch

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._

/**
  * Question: does the fetch unit need to communicate the predicted next address along with
  * instruction to the decode unit?
  * It will be 64-bit that will be rarely used(Only for branch instructions it will be used)
  */

/**
  * Functionality - decode unit communicates the pc of the instruction
  * its epecting through the decodeIssue.expeted port.
  * 
  * Results of branch predictions are returned on branchRes port.
  * 
  * cache is used to access instruction memory.
  *
  * additional information on ports can be found on common/ports.scala 
  */
class fetch extends Module {
  /**
    * Inputs and Outputs of the module
    */

  // interface with cache
  val cache = IO(new Bundle {
    val req   = DecoupledIO(UInt(64.W)) // address is 64 bits wide for 64-bit machine
    val resp  = Flipped(DecoupledIO(UInt(32.W))) // instructions are 32 bits wide
  })

  // issuing instructions to pc
  val decodeIssue = IO(new issueInstrFrmFetch)

  // receiving results of branches in order
  val branchRes   = IO(new branchResToFetch)

  /**
    * Internal of the module goes here
    */
}