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
  carryOutFence.ready := !results.io.deq.valid

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
  val commitResultReady = RegInit(false.B)
  val commitResult = Reg(new Bundle {
    val value = UInt(64.W)
    val rd = UInt(5.W)
    val opcode = UInt(7.W)
    val robAddr = UInt(robAddrWidth.W)
  })
  when(commitResultReady) { commitResultReady := !commit.fired 
  }.otherwise { commitResultReady := (results.io.deq.bits(0).asBool && results.io.deq.valid && fifo.io.deq.valid) }

  when(!commitResultReady) {
    commitResult.value := results.io.deq.bits(64,1)
    commitResult.rd := fifo.io.deq.bits(4,0)
    commitResult.opcode := fifo.io.deq.bits(11,5)
    commitResult.robAddr := results.commit_addr
  }

  commit.ready := commitResultReady
  commit.value := commitResult.value
  commit.rd := commitResult.rd
  commit.opcode := commitResult.opcode
  results.io.deq.ready := !commitResultReady && results.io.deq.bits(0).asBool
  fifo.io.deq.ready := !commitResultReady && results.io.deq.bits(0).asBool
  commit.robAddr := commitResult.robAddr
  /* commit.ready := (results.io.deq.bits(0) === 1.U) & results.io.deq.valid & fifo.io.deq.valid
  commit.value := results.io.deq.bits(64,1)
  commit.rd := fifo.io.deq.bits(4,0)
  commit.opcode := fifo.io.deq.bits(11,5)
  results.io.deq.ready := commit.fired
  fifo.io.deq.ready := commit.fired
  commit.robAddr := results.commit_addr */

  //printf(p"${commit}\n")

}

object robVerilog extends App {
  (new chisel3.stage.ChiselStage).emitVerilog(new rob)
}
