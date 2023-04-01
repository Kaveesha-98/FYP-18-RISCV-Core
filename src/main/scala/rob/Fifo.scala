package pipeline.fifo

import Chisel.log2Ceil
import chisel3._
import pipeline.ports._
import chisel3.util._

/**
  * FIFO IO with enqueue and dequeue ports using the ready/valid interface.
  */
class FifoIO[T <: Data](private val gen: T) extends Bundle {
  val enq = Flipped(new DecoupledIO(gen))
  val deq = new DecoupledIO(gen)
}

abstract class Fifo[T <: Data ]( gen: T, val depth: Int) extends Module  with RequireSyncReset{
  val io = IO(new FifoIO(gen))
  assert(depth > 0, "Number of buffer elements needs to be larger than 0")
}



class randomAccessFifo[T <: Data ]( gen: T, depth: Int) extends Fifo(gen:
  T, depth: Int) {

  class forwardPort extends Bundle {
    val addr = Input(UInt(log2Ceil(depth).W))
    val data = Output(gen)
  }

  class randomWrite extends Bundle {
    val data = Input(gen)
    val addr = Input(UInt(log2Ceil(depth).W))
    val valid = Input(Bool())
  }


  def counter(depth: Int , incr: Bool): (UInt , UInt) = {
    val cntReg = RegInit (0.U(log2Ceil(depth).W))
    val nextVal = Mux(cntReg === (depth -1).U, 0.U, cntReg + 1.U)
    when (incr) {
      cntReg := nextVal
    }
    (cntReg , nextVal)
  }

  // the register based memory
  val memReg = Mem(depth,gen)
  val incrRead = WireDefault (false.B)
  val incrWrite = WireDefault (false.B)
  val (readPtr , nextRead) = counter(depth , incrRead)
  val (writePtr , nextWrite ) = counter(depth , incrWrite )
  val emptyReg = RegInit(true.B)
  val fullReg = RegInit(false.B)

  val addr = IO(Output(UInt(log2Ceil(depth).W)))
  addr := writePtr

  //forward ports
  val forward1 = IO(new forwardPort)
  val forward2 = IO(new forwardPort)

  forward1.data := memReg(forward1.addr)
  forward2.data := memReg(forward2.addr)

  //random write port
  val writeportexec = IO(new randomWrite)

  when(writeportexec.valid){
    memReg(writeportexec.addr) := writeportexec.data
  }

  val writeportmem = IO(new randomWrite)

  when(writeportmem.valid) {
    memReg(writeportmem.addr) := writeportmem.data
  }

  when (io.enq.valid && !fullReg) {
    memReg(writePtr) := io.enq.bits
    emptyReg := false.B
    fullReg := nextWrite === readPtr
    incrWrite := true.B
  }

  when (io.deq.ready && !emptyReg) {
    fullReg := false.B
    emptyReg := nextRead === writePtr
    incrRead := true.B
  }

  io.deq.bits := memReg(readPtr)
  io.enq.ready := !fullReg
  io.deq.valid := !emptyReg
  //printf(p"$io\n")
}

class regFifo[T <: Data ]( gen: T, depth: Int) extends Fifo(gen:
  T, depth: Int) {

  def counter(depth: Int , incr: Bool): (UInt , UInt) = {
    val cntReg = RegInit (0.U(log2Ceil(depth).W))
    val nextVal = Mux(cntReg === (depth -1).U, 0.U, cntReg + 1.U)
    when (incr) {
      cntReg := nextVal
    }
    (cntReg , nextVal)
  }

  // the register based memory
  val memReg = Mem(depth , gen)
  val incrRead = WireDefault (false.B)
  val incrWrite = WireDefault (false.B)
  val (readPtr , nextRead) = counter(depth , incrRead)
  val (writePtr , nextWrite ) = counter(depth , incrWrite )
  val emptyReg = RegInit(true.B)
  val fullReg = RegInit(false.B)


  when (io.enq.valid && !fullReg) {
    memReg(writePtr) := io.enq.bits
    emptyReg := false.B
    fullReg := nextWrite === readPtr
    incrWrite := true.B
  }

  when (io.deq.ready && !emptyReg) {
    fullReg := false.B
    emptyReg := nextRead === writePtr
    incrRead := true.B
  }

  io.deq.bits := memReg(readPtr)
  io.enq.ready := !fullReg
  io.deq.valid := !emptyReg
  //printf(p"$io\n")
}
