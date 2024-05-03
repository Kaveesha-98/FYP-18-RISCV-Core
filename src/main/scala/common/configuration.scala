package pipeline.configuration

/**
  * * * * * IMPORTANT * * * * * 
  * There is only one maintainer of this file. Only the maintainer is only allowed
  * to make changes to this file in the *main* branch.
  * 
  * Maintainer: Kaveesha Yalegama
  */

import chisel3._
import chisel3.util._
import chisel3.experimental.BundleLiterals._
import chisel3.experimental.IO

object coreConfiguration {
    val robAddrWidth = 3
    val ramBaseAddress = 0x0000000010000000L
    val ramHighAddress = 0x000000001fffffffL
    val iCacheOffsetWidth = 2
    val iCacheLineWidth = 6
    val iCacheTagWidth = 32 - iCacheLineWidth - iCacheOffsetWidth - 2
    val iCacheBlockSize = (1 << iCacheOffsetWidth) // number of instructions
    val dCacheDoubleWordOffsetWidth = 3
    val dCacheLineWidth = 6
    val dCacheTagWidth = 32 - dCacheLineWidth - dCacheDoubleWordOffsetWidth - 3
    val dCacheBlockSize = (1 << dCacheDoubleWordOffsetWidth)
    val instructionBase = 0x0000000010000000L// = 0x0000000010000000L// = 0x0000000040000000L
}
