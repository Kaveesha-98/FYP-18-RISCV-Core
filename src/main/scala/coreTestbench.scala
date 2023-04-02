import chisel3._
import chisel3.util._
import chisel3.util.HasBlackBoxResource
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

class testbench extends Module {
/*   // once reset programLoader will send data from the lowest byte address
  // to the highest defined byte address
  val programLoader = IO(Input(new Bundle {
    val valid = Bool()
    val byte  = UInt(8.W)
  }))

  // transactions with memory get passed only when this is high
  val runProgram = IO(Input(Bool()))

  val mem = SyncReadMem (1024 , UInt (8.W))

  val waiting :: getReadReq :: reading :: Nil = Enum(3)
  val serviceState = RegInit(waiting)
  
  val inst :: data :: Nil = Enum(2)
  val servicing = RegInit(inst)

  val dut = Module(new core)

  val ports = VecInit(dut.iPort, dut.dPort)
  // round robin style of servicing requests
  when((serviceState === waiting && !ports(servicing).ARVALID) || (ports(servicing).RVALID && ports(servicing).RREADY && ports(servicing).RLAST)) { servicing := ~servicing }

  val readRequest = Reg(new Bundle {
    // can assume all requests require 4 bytes per burst
    val address = UInt(32.W)
    val arlen   = UInt(8.W)
  })

  switch(serviceState){
    is(waiting) {
      when(ports(servicing).ARVALID) {
        readRequest.address := ports(servicing).ARADDR
        readRequest.arlen   := ports(servicing).ARLEN
      }
    }
    is(reading) {
      when(ports(servicing).RVALID && ports(servicing).RREADY) {
        readRequest.address := readRequest.address + 4.U // reads 4 bytes at a time
        readRequest.arlen   := readRequest.arlen - 1.U
      }
    }
  }

  switch(serviceState) {
    is(waiting) {
      when(ports(servicing).ARVALID) { serviceState := getReadReq }
    }
    is(getReadReq) {
      serviceState := reading
    }
    is(reading) {
      when(ports(servicing).RVALID && ports(servicing).RREADY && ports(servicing).RLAST) { servicing := waiting }
    }

    Seq(inst, data).foreach( interface => {
      ports(interface).ARREADY := ((serviceState === waiting) && (servicing === interface))
      ports(interface).RVALID := ((serviceState === reading) && (servicing === interface))
      ports(interface).RDATA := 
    })
  } */
}
