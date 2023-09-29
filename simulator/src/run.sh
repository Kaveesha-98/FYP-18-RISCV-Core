#! /bin/sh
cd ../..

sbt "runMain system"
cp system.v simulator/src/

cd simulator/src/

echo '/* verilator lint_off UNUSED */' | cat - system.v > temp && mv temp system.v
echo '/* verilator lint_off DECLFILENAME */' | cat - system.v > temp && mv temp system.v

cp ../../dCacheRegisters.v .
cp ../../iCacheRegisters.v .

echo '/* verilator lint_off UNUSED */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v
echo '/* verilator lint_off DECLFILENAME */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v
echo '/* verilator lint_off VARHIDDEN */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v
echo '/* verilator lint_off UNUSED */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v
echo '/* verilator lint_off DECLFILENAME */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v
echo '/* verilator lint_off VARHIDDEN */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v
echo '/* verilator lint_off VARHIDDEN */' | cat - system.v > temp && mv temp system.v
echo '/* verilator lint_off WIDTH */' | cat - system.v > temp && mv temp system.v

verilator -Wall --trace -cc system.v
cd obj_dir/
make -f Vsystem.mk

cd ..

g++ -O3 -I /usr/share/verilator/include -I obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp bench.cpp obj_dir/Vsystem__ALL.a -o bench.out

./bench.out