#include <stdio.h>
#include <stdlib.h>
#include "VdCache.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

void tick(int tickcount, VdCache *tb, VerilatedVcdC* tfp){
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
	VdCache *tb = new VdCache;
	
	Verilated::traceEverOn(true);
	VerilatedVcdC* tfp = new VerilatedVcdC;
	tb->trace(tfp, 99);
	tfp->open("dCache_trace.vcd");
	
	for(int i = 0; i < 20; i++){
		tick(++tickcount, tb, tfp);
	}
}

