package pipeline.rob

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._

class robAllocate extends composableInterface {
  val instOpcode = Input(UInt(7.W))
  val robAddr = Output(UInt(robAddrWidth.W))
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
}

class pullResultsToRob extends composableInterface {
  val robAddr     = Input(UInt(robAddrWidth.W))
  val execResult  = Input(UInt(64.W))
}

class rob extends Module {
  // defining ports
  val carryOutFence = IO(new composableInterface)
  
  // 
  val fromDecode = IO(new robAllocate)

  val fromExec = IO(new pullResultsToRob)

  val fromMem = IO(new pullResultsToRob)

  val commitStore = IO(new composableInterface)

  val pushFenceDCache = IO(new composableInterface)

  val waitForCleanDCache = IO(new composableInterface)

  val pushFenceFetch = IO(new composableInterface)
}