VPATH = ../scala:../cache:../common:../decode:../exec:../fetch:./memAccess:../rob:../testbench

runLockStep: lock_step_run.out fyp18-riscv-emulator/src/Image
	./lock_step_run.out

lock_step_run.out: lock_step_run.cpp fyp18-riscv-emulator/src/emulator.h fyp18-riscv-emulator/src/constants.h simulator/src/simulator.h simulator/src/obj_dir
	g++ -O3 -I /usr/share/verilator/include -I simulator/src/obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp lock_step_run.cpp simulator/src/obj_dir/Vsystem__ALL.a -o lock_step_run.out

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
sim:
	# Change instructionBase in configuration file
	mv src/main/scala/common/configuration.scala configuration.txt
	sed 's/instructionBase/instructionBase = 0x0000000010000000L\/\//' configuration.txt > src/main/scala/common/configuration.scala
	sbt "runMain system"
	# Restoring the original configuration
	mv configuration.txt src/main/scala/common/configuration.scala
	cp system.v simulator/src/
	cd simulator/src/; \
	cp ../../dCacheRegisters.v .; \
	cp ../../iCacheRegisters.v .; \
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

simulator/src/bench.out: simulator/src/obj_dir simulator/src/simulator.h simulator/src/bench.cpp
	cd simulator/src; \
	g++ -O3 -I /usr/share/verilator/include -I obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp bench.cpp obj_dir/Vsystem__ALL.a -o bench.out

runSim: simulator/src/bench.out
	cd simulator/src/; \
	./bench.out

bintoh :
	echo "#include <stdio.h>" > bintoh.c
	echo "int main(int argc,char ** argv) {if(argc==1) return -1; int c, p=0; printf( \"static const unsigned char %s[] = {\", argv[1] ); while( ( c = getchar() ) != EOF ) printf( \"0x%02x,%c\", c, (((p++)&15)==15)?10:' '); printf( \"};\" ); return 0; }" >> bintoh.c
	gcc bintoh.c -o bintoh

prog.h : Image1 bintoh
	./bintoh program < $< > $@
	# WARNING: sixtyfourmb.dtb MUST hvave at least 16 bytes of buffer room AND be 16-byte aligned.
	#  dtc -I dts -O dtb -o sixtyfourmb.dtb sixtyfourmb.dts -S 1536

make zynq:
	# Change instructionBase in configuration file
	mv src/main/scala/common/configuration.scala configuration.txt
	sed 's/instructionBase/instructionBase = 0x0000000040000000L\/\//' configuration.txt > src/main/scala/common/configuration.scala
	sbt "runMain core"
	# Restoring the original configuration
	mv configuration.txt src/main/scala/common/configuration.scala
	# Contains the program that is run until the PS sets up RAM
	sbt "runMain bootROM"
	# Contains the clint, and the register to signal to the core that the image is loaded to RAM
	sbt "runMain testbench.psClint"