package pipeline.stack

import Chisel.log2Ceil
import chisel3._
import chisel3.util._
import pipeline.ports.ras_constants._

class stack[T <: Data ](gen: T, depth: Int) extends Module{
  val io = IO(new Bundle {
    val valid   = Input(Bool())
    val op      = Input(UInt(2.W))
    val dataIn  = Input(gen)
    val dataOut = Output(gen)
  })


  // register and mem defs
  val stack = Mem(depth,gen)
  val sp    = RegInit(0.U(log2Ceil(depth).W))

  io.dataOut := stack(sp)

  when(io.valid){
    when(io.op === pop.U){
      sp := sp - 1.U
    }.elsewhen(io.op === push.U){
      sp := sp + 1.U
      stack(sp + 1.U) := io.dataIn
    }.otherwise {
      stack(sp) := io.dataIn
    }
  }

  //debug
  val sp_o = IO(Output(UInt(log2Ceil(depth).W)))
  sp_o := sp


}
