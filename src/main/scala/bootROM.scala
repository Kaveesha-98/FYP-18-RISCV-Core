import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

import pipeline.ports._
import pipeline.memAccess.AXI

import java.io.FileInputStream

class bootROM extends Module {
  val req = IO(Flipped(new AXI))

  def readBinaryFile(fileName: String): Array[Byte] = {
    val fileInputStream = new FileInputStream(fileName)
    val byteLength = fileInputStream.available()
    val bytesArray = new Array[Byte](byteLength)
    fileInputStream.read(bytesArray)
    fileInputStream.close()
    bytesArray
  }

  val ROM = VecInit(readBinaryFile("src/main/resources/bootrom/ps_boot.bin").toSeq.map(_&0x00ff).map(_.U(8.W)))

  val readRequestBuffer = RegInit(new Bundle {
    val valid = Bool()
    val address = UInt(32.W)
    val size = UInt(3.W)
    val len = UInt(8.W)
    val id = req.ARID.cloneType
  } Lit(_.valid -> false.B))

  when(req.ARREADY && req.ARVALID) { 
    readRequestBuffer.valid := true.B
    readRequestBuffer.address := req.ARADDR
    readRequestBuffer.len := req.ARLEN
    readRequestBuffer.size := req.ARSIZE
    readRequestBuffer.id := req.ARID
  }

  when(readRequestBuffer.valid && req.RREADY) {
    readRequestBuffer.len := readRequestBuffer.len - 1.U
    readRequestBuffer.address := readRequestBuffer.address + 4.U
    when(!readRequestBuffer.len.orR) { readRequestBuffer.valid := false.B }
  }

  req.RDATA := Cat(VecInit.tabulate(4)(i => ROM(Cat(readRequestBuffer.address(31, 2), 0.U(2.W)) + i.U)).reverse)
  req.RID := readRequestBuffer.id
  req.RLAST := !readRequestBuffer.len.orR
  req.RRESP := 0.U
  req.RVALID := readRequestBuffer.valid

  req.ARREADY := !readRequestBuffer.valid

  req.AWREADY := false.B
  req.WREADY := false.B

  req.BID := 0.U
  req.BRESP := 0.U
  req.BVALID := false.B
}

object bootROM extends App {
  emitVerilog(new bootROM)
}
