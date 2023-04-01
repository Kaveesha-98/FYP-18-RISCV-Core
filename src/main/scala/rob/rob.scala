package pipeline.rob

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.fifo._

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
}

class commitInstruction extends composableInterface{
  val rd = Output(UInt(5.W))
  val value = Output(UInt(64.W))
  val opcode = Output(UInt(7.W))
}

class pullResultsToRob extends composableInterface {
  val robAddr     = Input(UInt(robAddrWidth.W))
  val execResult  = Input(UInt(64.W))
}

class rob extends Module {
  // defining ports
  val carryOutFence = IO(new composableInterface)

  val fromDecode = IO(new robAllocate)

  val fromExec = IO(new pullResultsToRob)

  val fromMem = IO(new pullResultsToRob)

  val commit = IO(new commitInstruction)


  // logic starts here

  // results fifo
  // value(64 bits) | ready to commit (1 bit)
  val results = Module(new randomAccessFifo(UInt(65.W),scala.math.pow(2,robAddrWidth).asInstanceOf[Int]))
  fromDecode.robAddr := results.addr
  results.io.enq.bits := 0.U

  // ordinary fifo
  // opcode | rd
  val fifo = Module(new regFifo(UInt(12.W), scala.math.pow(2, robAddrWidth).asInstanceOf[Int]))
  fifo.io.enq.bits := Cat(fromDecode.instOpcode,fromDecode.rd)
  fifo.io.enq.valid := fromDecode.fired

  fromDecode.ready := results.io.enq.ready & fifo.io.enq.ready
  results.io.enq.valid := fromDecode.fired

  //connect forwarding ports
  fromDecode.fwdrs1.value := results.forward1.data(64,1)
  results.forward1.addr := fromDecode.fwdrs1.robAddr
  fromDecode.fwdrs1.valid := results.forward1.data(0)

  fromDecode.fwdrs2.value := results.forward2.data(64,1)
  results.forward2.addr := fromDecode.fwdrs2.robAddr
  fromDecode.fwdrs2.valid := results.forward2.data(0)

  // fence ready
  carryOutFence.ready := !results.io.deq.valid

  // write results from exec
  fromExec.ready := !results.io.deq.ready
  results.writeportexec.data := Cat(fromExec.execResult,1.U(1.W))
  results.writeportexec.addr := fromExec.robAddr
  results.writeportexec.valid := fromExec.fired

  // write results from mem
  fromMem.ready := !results.io.deq.ready
  results.writeportmem.data := Cat(fromMem.execResult, 1.U(1.W))
  results.writeportmem.addr := fromMem.robAddr
  results.writeportmem.valid := fromMem.fired

  // commit
  commit.ready := (results.io.deq.bits(0) === 1.U) & results.io.deq.valid & fifo.io.deq.valid
  commit.value := results.io.deq.bits(64,1)
  commit.rd := fifo.io.deq.bits(4,0)
  commit.opcode := fifo.io.deq.bits(11,5)
  results.io.deq.ready := commit.fired
  fifo.io.deq.ready := commit.fired

  printf(p"${commit}\n")

}

object Verilog extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new rob)
}