package pipeline.rob

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

// definition of all ports can be found here
import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.fifo._


class rob extends Module {
  // defining ports
  val carryOutFence = IO(new composableInterface)

  val fromDecode = IO(new robAllocate)

  val fromExec = IO(new pullExecResultToRob)

  val fromMem = IO(new pullMemResultToRob)

  val commit = IO(new commitInstruction)


  // logic starts here

  // results fifo
  // exception occured(1bit) | mcause(64 bits) | value(64 bits) | ready to commit (1 bit)
  val results = Module(new randomAccessFifo(UInt(130.W),scala.math.pow(2,robAddrWidth).asInstanceOf[Int]))
  fromDecode.robAddr := results.addr
  results.io.enq.bits := 0.U

  // ordinary fifo
  // pc | opcode | rd
  val fifo = Module(new regFifo(UInt(76.W), scala.math.pow(2, robAddrWidth).asInstanceOf[Int]))
  fifo.io.enq.bits := Cat(fromDecode.pc,Cat(fromDecode.instOpcode,fromDecode.rd))
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
  val is_fence = commit.opcode === "b0001111".U
  carryOutFence.ready := is_fence

  // write results from exec
  fromExec.ready := true.B //results.io.deq.valid
  results.writeportexec.data := Cat(Cat(fromExec.execeptionOccured,fromExec.mcause),Cat(fromExec.execResult,1.U(1.W)))
  results.writeportexec.addr := fromExec.robAddr
  results.writeportexec.valid := fromExec.fired

  // write results from mem
  fromMem.ready := results.io.deq.valid
  results.writeportmem.data := Cat(fromMem.writeBackData, 1.U(1.W))
  results.writeportmem.addr := fromMem.robAddr
  results.writeportmem.valid := fromMem.fired

  // commit
  commit.ready := (results.io.deq.bits(0) === 1.U || is_fence) & results.io.deq.valid & fifo.io.deq.valid
  commit.writeBackData := results.io.deq.bits(64,1)
  commit.rdAddr := fifo.io.deq.bits(4,0)
  commit.opcode := fifo.io.deq.bits(11,5)
  results.io.deq.ready := commit.fired
  fifo.io.deq.ready := commit.fired
  commit.robAddr := results.commit_addr
  commit.execptionOccured := results.io.deq.bits(129)
  commit.mcause := results.io.deq.bits(128,65)
  commit.mepc := fifo.io.deq.bits(75,12)

  // reset rob when an exception is committed
  when(commit.execptionOccured & commit.fired){
    results.reset := 1.U
    fifo.reset := 1.U
  }

  //printf(p"${commit}\n")

}

object robVerilog extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new rob)
}
