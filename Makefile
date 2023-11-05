VPATH = ../scala:../cache:../common:../decode:../exec:../fetch:./memAccess:../rob:../testbench

lock_step_run: bench.out fyp18-riscv-emulator/src/Image
	./bench.out

bench.out: qemu_mimic.cpp fyp18-riscv-emulator/src/emulator.h fyp18-riscv-emulator/src/constants.h simulator/src/simulator.h simulator/src/obj_dir
	g++ -O3 -I /usr/share/verilator/include -I simulator/src/obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp qemu_mimic.cpp simulator/src/obj_dir/Vsystem__ALL.a -o bench.out

simulator/src/obj_dir: simulator/src/system.v simulator/src/dCacheRegisters.v simulator/src/iCacheRegisters.v
	cd simulator/src/; \
	echo '/* verilator lint_off UNUSED */' | cat - system.v > temp && mv temp system.v; \
	echo '/* verilator lint_off DECLFILENAME */' | cat - system.v > temp && mv temp system.v; \
	echo '/* verilator lint_off UNUSED */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v; \
	echo '/* verilator lint_off DECLFILENAME */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v; \
	echo '/* verilator lint_off VARHIDDEN */' | cat - dCacheRegisters.v > temp && mv temp dCacheRegisters.v; \
	echo '/* verilator lint_off UNUSED */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v; \
	echo '/* verilator lint_off DECLFILENAME */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v; \
	echo '/* verilator lint_off VARHIDDEN */' | cat - iCacheRegisters.v > temp && mv temp iCacheRegisters.v; \
	echo '/* verilator lint_off VARHIDDEN */' | cat - system.v > temp && mv temp system.v; \
	echo '/* verilator lint_off WIDTH */' | cat - system.v > temp && mv temp system.v; \
	verilator -Wall --trace -cc system.v; \
	cd obj_dir/; \
	make -f Vsystem.mk; \

targets := $(wildcard src/*.scala)
simulator/src/system.v: src/main/scala/decode/decode.scala src/main/scala/testbench/uart.scala
	sbt "runMain system"
	cp system.v simulator/src/
	cd simulator/src/; \
	cp ../../dCacheRegisters.v .; \
	cp ../../iCacheRegisters.v .; \