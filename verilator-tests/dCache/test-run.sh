#! /bin/sh
cd ../..
sbt "runMain pipeline.memAccess.cache.dCache"

cd verilator-tests/dCache

mv ../../dCache.v .
mv ../../dCacheRegisters.v .

echo '/* verilator lint_off UNUSED */' | cat - dCache.v > temp && mv temp dCache.v
echo '/* verilator lint_off DECLFILENAME */' | cat - dCache.v > temp && mv temp dCache.v
echo '/* verilator lint_off VARHIDDEN */' | cat - dCache.v > temp && mv temp dCache.v

echo '/* verilator lint_off UNUSED */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v
echo '/* verilator lint_off DECLFILENAME */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v
echo '/* verilator lint_off VARHIDDEN */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v

verilator -Wall --trace -cc dCache.v

cd obj_dir

make -f VdCache.mk

cd ..

g++ -I /usr/share/verilator/include -I obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp dCachetest.cpp obj_dir/VdCache__ALL.a -o dCache
