#! /bin/sh
cd ../..

echo "package pipeline.configuration

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
    val ramBaseAddress = 0x0000000080000000L
    val ramHighAddress = 0x0000000090000000L
    val iCacheOffsetWidth = 2
    val iCacheLineWidth = 6
    val iCacheTagWidth = 32 - iCacheLineWidth - iCacheOffsetWidth - 2
    val iCacheBlockSize = (1 << iCacheOffsetWidth) // number of instructions
    val dCacheDoubleWordOffsetWidth = 3
    val dCacheLineWidth = 6
    val dCacheTagWidth = 32 - dCacheLineWidth - dCacheDoubleWordOffsetWidth - 3
    val dCacheBlockSize = (1 << dCacheDoubleWordOffsetWidth)
    val instructionBase = 0x0000000080000000L
}" > src/main/scala/common/configuration.scala

sbt "runMain testbench"

cd verilator-tests/coretest

mv ../../testbench.v .
mv ../../dCacheRegisters.v .
mv ../../iCacheRegisters.v .

echo '/* verilator lint_off UNUSED */' | cat - testbench.v > temp && mv temp testbench.v
echo '/* verilator lint_off DECLFILENAME */' | cat - testbench.v > temp && mv temp testbench.v
echo '/* verilator lint_off VARHIDDEN */' | cat - testbench.v > temp && mv temp testbench.v

echo '/* verilator lint_off UNUSED */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v
echo '/* verilator lint_off DECLFILENAME */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v
echo '/* verilator lint_off VARHIDDEN */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v

echo '/* verilator lint_off UNUSED */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v
echo '/* verilator lint_off DECLFILENAME */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v
echo '/* verilator lint_off VARHIDDEN */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v

verilator -Wall --trace -cc testbench.v

cd obj_dir

make -f Vtestbench.mk

cd ..

g++ -I /usr/share/verilator/include -I obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp testbenchtest.cpp obj_dir/Vtestbench__ALL.a -o testbench

./testbench

#gtkwave add_trace.vcd