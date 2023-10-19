package testbench

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.memAccess.AXI
import os.read
import os.readLink
import os.write

class uart extends Module {
  val client = IO(Flipped(new AXI))

  val readRequestBuffer = RegInit(new Bundle {
    val valid = Bool()
    val address = UInt(32.W)
    val size = UInt(3.W)
    val len = UInt(8.W)
    val id = client.ARID.cloneType
  } Lit(_.valid -> false.B))

  val writeRequestBuffer = RegInit(new Bundle {
    val address = new Bundle {
      val valid = Bool()
      val offset = UInt(32.W)
      val size = UInt(3.W)
      val len = UInt(8.W)
      val id = client.AWID.cloneType
    }
    val data = new Bundle {
      val valid = Bool()
      val data = UInt(32.W)
      val last = Bool()
      val strb = UInt(4.W)
    }
  } Lit(_.address.valid -> false.B, _.data.valid -> false.B))

  when(client.ARREADY && client.ARVALID) { 
    readRequestBuffer.valid := true.B
    readRequestBuffer.address := client.ARADDR
    readRequestBuffer.len := client.ARLEN
    readRequestBuffer.size := client.ARSIZE
    readRequestBuffer.id := client.ARID
  }

  when(readRequestBuffer.valid && client.RREADY) {
    readRequestBuffer.len := readRequestBuffer.len - 1.U
    when(!readRequestBuffer.len.orR) { readRequestBuffer.valid := false.B }
  }
  val mtime = RegInit(0.U(64.W))
  mtime := mtime + 1.U
  val mtimeRead = Reg(UInt(64.W))
  when(client.ARREADY && client.ARVALID) {
    mtimeRead := mtime
  }

  // client.RDATA := Mux((readRequestBuffer.address&("hff".U)) === ("h2c".U), 8.U, 0.U)
  client.RDATA := 8.U
  switch(readRequestBuffer.address) {
    is("he000002c".U) { client.RDATA := 8.U }
    is("h0200bff8".U) { client.RDATA := Mux(readRequestBuffer.len.orR, mtimeRead(31, 0), mtimeRead(63, 32)) }
  }
  client.RID := readRequestBuffer.id
  client.RLAST := !readRequestBuffer.len.orR
  client.RRESP := 0.U
  client.RVALID := readRequestBuffer.valid

  val putChar = IO(Output(new Bundle {
    val valid = Bool()
    val byte = UInt(8.W)
  }))
  putChar.valid := Seq((writeRequestBuffer.address.offset&("hff".U)) === ("h30".U), writeRequestBuffer.address.valid, writeRequestBuffer.data.valid).reduce(_ && _)
  putChar.byte := writeRequestBuffer.data.data(7, 0)

  when(writeRequestBuffer.address.valid && writeRequestBuffer.data.valid) {
    writeRequestBuffer.data.valid := false.B
    when(writeRequestBuffer.data.last) {
      writeRequestBuffer.address.valid := false.B
    }
  }

  when(client.AWREADY && client.AWVALID) {
    writeRequestBuffer.address.valid := true.B
    writeRequestBuffer.address.offset := client.AWADDR
    writeRequestBuffer.address.id := client.AWID
    writeRequestBuffer.address.len := client.AWLEN
    writeRequestBuffer.address.size := client.AWSIZE
  }

  when(client.WREADY && client.WVALID) {
    writeRequestBuffer.data.valid := true.B
    writeRequestBuffer.data.data := client.WDATA
    writeRequestBuffer.data.last := client.WLAST
    writeRequestBuffer.data.strb := client.WSTRB
  }

  client.ARREADY := !readRequestBuffer.valid

  client.AWREADY := !writeRequestBuffer.address.valid
  client.WREADY := !writeRequestBuffer.data.valid || writeRequestBuffer.address.valid

  client.BID := writeRequestBuffer.address.id
  client.BRESP := 0.U
  client.BVALID := writeRequestBuffer.address.valid && writeRequestBuffer.data.valid && writeRequestBuffer.data.last
}

object uart extends App {
  emitVerilog(new uart)
}