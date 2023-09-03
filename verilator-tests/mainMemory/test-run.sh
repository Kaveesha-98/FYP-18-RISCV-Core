#! /bin/sh
cd ../..
sbt "runMain testbench.mainMemory"

cd verilator-tests/mainMemory

cp ../../mainMemory.v .

echo '/* verilator lint_off UNUSED */' | cat - mainMemory.v > temp && mv temp mainMemory.v
echo '/* verilator lint_off DECLFILENAME */' | cat - mainMemory.v > temp && mv temp mainMemory.v


verilator -Wall --trace -cc mainMemory.v

cd obj_dir

make -f VmainMemory.mk

cd ..

g++ -I /usr/share/verilator/include -I obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp mainMemorytest.cpp obj_dir/VmainMemory__ALL.a -o mainMemory
