#! /bin/sh
cd ../..
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