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
  // exception occured(1bit) | mcause(64 bits) | value(64 bits) | eflags(5 bits) | ready to commit (1 bit)
  // exception occured(1bit) | mcause(64 bits) | value(64 bits) | eflags(5 bits) | fwrite(1 bit) | ready to commit (1 bit)
  val results = Module(new randomAccessFifo(UInt(135.W),scala.math.pow(2,robAddrWidth).asInstanceOf[Int]))
  fromDecode.robAddr := results.addr
  results.io.enq.bits := 0.U

  // ordinary fifo
  // pc | opcode | rd
  val fifo = Module(new regFifo(UInt(96.W), scala.math.pow(2, robAddrWidth).asInstanceOf[Int]))
  fifo.io.enq.bits := Cat(fromDecode.pc,fromDecode.inst)
  fifo.io.enq.valid := fromDecode.fired

  fromDecode.ready := results.io.enq.ready & fifo.io.enq.ready
  results.io.enq.valid := fromDecode.fired

  //connect forwarding ports
  fromDecode.fwdrs1.value := results.forward1.data(69,6)
  results.forward1.addr := fromDecode.fwdrs1.robAddr
  fromDecode.fwdrs1.valid := results.forward1.data(0)

  fromDecode.fwdrs2.value := results.forward2.data(69,6)
  results.forward2.addr := fromDecode.fwdrs2.robAddr
  fromDecode.fwdrs2.valid := results.forward2.data(0)

  // fence ready
  val is_fence = commit.inst(6,0) === "b0001111".U && !fifo.isEmpty
  carryOutFence.ready := is_fence

  // write results from exec
  fromExec.ready := true.B //results.io.deq.valid
  //results.writeportexec.data := Cat(Cat(fromExec.execeptionOccured,fromExec.mcause),Cat(fromExec.execResult,1.U(1.W)))
  //results.writeportexec.data := Cat(Cat(Cat(fromExec.execeptionOccured,fromExec.mcause),Cat(fromExec.execResult,fromExec.eflags)),Cat(fromExec.fwrite,1.U(1.W)))
  results.writeportexec.data := Cat(fromExec.execeptionOccured,fromExec.mcause,fromExec.execResult,fromExec.eflags,1.U(1.W))
  results.writeportexec.addr := fromExec.robAddr
  results.writeportexec.valid := fromExec.fired

  // write results from mem
  fromMem.ready := results.io.deq.valid
  results.writeportmem.data := Cat(fromMem.writeBackData,0.U(5.W), 1.U(1.W))
  results.writeportmem.addr := fromMem.robAddr
  results.writeportmem.valid := fromMem.fired

  // commit
  commit.ready := (results.io.deq.bits(0) === 1.U || is_fence) & results.io.deq.valid & fifo.io.deq.valid
  commit.writeBackData := results.io.deq.bits(69,6)
  // commit.rdAddr := fifo.io.deq.bits(4,0)
  commit.inst := fifo.io.deq.bits(31,0)
  results.io.deq.ready := commit.fired
  fifo.io.deq.ready := commit.fired
  commit.robAddr := results.commit_addr
  commit.execptionOccured := results.io.deq.bits(134)
  commit.mcause := results.io.deq.bits(133,70)
  commit.mepc := fifo.io.deq.bits(95,32)
  commit.eflags := results.io.deq.bits(5,1)
  // commit.fwrite := results.io.deq.bits(1)

  // reset rob when an exception is committed
  when(commit.execptionOccured & commit.fired){
    results.reset := 1.U
    fifo.reset := 1.U
  }


  //printf(p"${commit}\n")

}
