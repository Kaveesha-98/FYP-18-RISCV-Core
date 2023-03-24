#! /bin/sh

mkdir $1 && cd $1

echo "#include <stdio.h>
#include <stdlib.h>
#include \"V$1.h\"
#include \"verilated.h\"
#include \"verilated_vcd_c.h\"

void tick(int tickcount, V$1 *tb, VerilatedVcdC* tfp){
	tb->eval();
	if (tfp){
		tfp->dump(tickcount*10 - 2);
	}
	tb->clock = 1;
	tb->eval();
	if(tfp){
		tfp->dump(tickcount*10);
	}
	tb->clock = 0;
	tb->eval();
	if(tfp){
		tfp->dump(tickcount*10 + 5);
		tfp->flush();
	}
}

int main(int argc, char **argv){

	unsigned tickcount = 0;

	// Call commandArgs first!
	Verilated::commandArgs(argc, argv);
	
	//Instantiate our design
	V$1 *tb = new V$1;
	
	Verilated::traceEverOn(true);
	VerilatedVcdC* tfp = new VerilatedVcdC;
	tb->trace(tfp, 99);
	tfp->open(\"$1_trace.vcd\");
	
	for(int i = 0; i < 20; i++){
		tick(++tickcount, tb, tfp);
	}
}
" > $1test.cpp

echo "#! /bin/sh
cd ../../ProjectOdessy
sbt \"runMain $2\"

cd ../verilator-tests/$1

mv ../../ProjectOdessy/$1.v .

echo '/* verilator lint_off UNUSED */' | cat - $1.v > temp && mv temp $1.v
echo '/* verilator lint_off DECLFILENAME */' | cat - $1.v > temp && mv temp $1.v


verilator -Wall --trace -cc $1.v

cd obj_dir

make -f V$1.mk

cd ..

g++ -I /usr/share/verilator/include -I obj_dir /usr/share/verilator/include/verilated.cpp /usr/share/verilator/include/verilated_vcd_c.cpp $1test.cpp obj_dir/V$1__ALL.a -o $1" > test-run.sh
