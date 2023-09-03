package testbench

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

import pipeline.ports._
import pipeline.configuration.coreConfiguration._
import pipeline.memAccess.AXI
import os.write
import pipeline.decode.constants

class mainMemory(
  addressBitSize:Int = 28,
  latency:Int = 1 // this variable changes nothing for now!!!
  ) extends Module {
  // toggle ON when kernel Image has been loaded
  val programmed = RegInit(false.B)

  // single cycle logic high to indicate that the memory has been programmed by an external entity
  val finishedProgramming = IO(Input(Bool()))

  when(finishedProgramming) { programmed := true.B }

  val memory = SyncReadMem ((1 << addressBitSize) , UInt (8.W))

  // External programmer
  val programmer = IO(Input(new Bundle {
    val valid   = Bool()
    val byte    = UInt(8.W)
    val offset  = UInt(addressBitSize.W)
  }))

  when (!programmed && programmer.valid) { memory.write(programmer.offset, programmer.byte) }

  // connection with core pipeline
  val clients = IO(Flipped(Vec(2, (new AXI))))
  val instruction :: data :: Nil = Enum(2)

  val servicing = RegInit(VecInit.fill(2)(new Bundle {
    val valid = Bool()
    val address = UInt(addressBitSize.W)
    val id = clients(0).ARID.cloneType
    val beatsRemaining = UInt(8.W)
  } Lit(_.valid -> false.B)))

  // accepting read requests
  (clients zip servicing)
  .foreach{ case(client, buffer) => 
    when(client.ARREADY && client.ARVALID) { 
      buffer.valid := true.B
      buffer.address := (client.ARADDR&(~(3.U(32.W)))) + 4.U
      buffer.id := client.ARID
      buffer.beatsRemaining := client.ARLEN
    } 
  }

  // read response buffer
  val readBackBuffers = RegInit(VecInit.fill(2)(new Bundle {
    val valid = Bool()
    val id = clients(0).RID.cloneType
    val data = clients(0).RDATA.cloneType // should be 32-bits
    val last = Bool()
  } Lit(_.valid -> false.B)))

  // servicing a read request
  (clients zip (servicing zip readBackBuffers))
  .foreach{ case(client, (request, response)) =>
    when(client.RREADY || !response.valid) {
      response.valid := request.valid
      response.data := Cat(Seq.tabulate(4)(i => memory.read(i.U + Mux(request.valid, request.address, (client.ARADDR&(~(3.U(32.W))))))).reverse)
      response.last := !(request.beatsRemaining.orR)
      response.id := request.id

      when(request.valid) {
        when(!request.beatsRemaining.orR) { request.valid := false.B }
        .otherwise { request.beatsRemaining := (request.beatsRemaining - 1.U) }
      }
    }  
    client.RVALID := response.valid
    client.ARREADY := Seq(programmed, !response.valid, !request.valid).reduce(_ && _)

    client.RDATA := response.data
    client.RID := response.id
    client.RLAST := response.last
    client.RRESP := 0.U
  }

  // write buffers
  val writeBuffers = RegInit(new Bundle {
    val addressValid = Bool()
    val address   = UInt(addressBitSize.W)
    val dataValid = Bool()
    val dataLast = Bool()
    val dataMask = clients(1).WSTRB.cloneType
    val data = clients(1).WDATA.cloneType // should be 32-bit
    val id = clients(1).AWID.cloneType
  } Lit(_.addressValid -> false.B, _.dataValid -> false.B))

  // finish writing memory
  when(writeBuffers.addressValid && writeBuffers.dataValid) {
   writeBuffers.dataValid := false.B
   when(writeBuffers.dataLast) { writeBuffers.addressValid := false.B } 
  }

  // accepting a new write request
  when(clients(data).AWVALID && clients(data).AWREADY) {
    writeBuffers.addressValid := true.B
    writeBuffers.address := clients(data).AWADDR
    writeBuffers.id := clients(data).AWID
  }

  // accepting new write data
  when(clients(data).WVALID && clients(data).WREADY) {
    writeBuffers.data := clients(data).WDATA
    writeBuffers.dataLast := clients(data).WLAST
    writeBuffers.dataValid := true.B
    writeBuffers.dataMask := clients(data).WSTRB
  }

  // writing to memory
  when(writeBuffers.addressValid && writeBuffers.dataValid) {
    Seq.tabulate(4)(i => (i, (writeBuffers.data >> (i*8))(7, 0), writeBuffers.dataMask(i)))
    .foreach{ case(offset, data, maskBit) => when(maskBit.asBool) { memory.write(writeBuffers.address + offset.U, data) } }

    writeBuffers.address := writeBuffers.address + 4.U
  }
  
  // write response buffer
  val writeFinished = RegInit(false.B)
  when(writeBuffers.addressValid && writeBuffers.dataLast && writeBuffers.dataValid) { writeFinished := true.B }
  .elsewhen(clients(data).BVALID && clients(data).BREADY) { writeFinished := false.B }

  clients(data).AWREADY := (!writeBuffers.addressValid && !writeFinished)
  clients(data).WREADY := (!writeBuffers.dataValid || writeBuffers.addressValid)
  clients(data).BVALID := writeFinished

  clients(data).BRESP := 0.U
  clients(data).BID := writeBuffers.id

  val writing :: reading :: Nil = Enum(2)
  val arbiter = RegInit(reading)
  switch(arbiter) {
    is(reading) {
      clients(data).AWREADY := false.B
      when(
        (!servicing(data).valid && !clients(data).ARVALID) ||
        Seq(clients(data).RREADY, clients(data).RVALID, clients(data).RLAST).reduce(_ && _) 
      ) {
        arbiter := writing
      }
    }
    is(writing) {
      clients(data).ARREADY := false.B
      when(
        (!writeBuffers.addressValid && !clients(data).AWVALID) ||
        (clients(data).WVALID && clients(data).WREADY) 
      ) {
        arbiter := reading
      }
    }
  }

  clients(instruction).BID := 0.U
  clients(instruction).BVALID := false.B
  clients(instruction).BRESP := 0.U
  clients(instruction).AWREADY := false.B
  clients(instruction).WREADY := false.B

  val externalProbe = IO(new Bundle {
    val offset = Input(UInt(addressBitSize.W))
    val accessLong = Output(UInt(64.W))
  })

  externalProbe.accessLong := Cat(Seq.tabulate(8)(i => memory.read(i.U + externalProbe.offset)).reverse)
}

object mainMemory extends App {
  emitVerilog(new mainMemory)
}
