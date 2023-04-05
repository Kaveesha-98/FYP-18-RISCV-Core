#include <stdio.h>
#include <stdlib.h>
#include "Vtestbench.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <fstream>
#include <iterator>
#include <vector>
#include <iostream>
#include <string>

using namespace std;

void tick(int tickcount, Vtestbench *tb, VerilatedVcdC* tfp){
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
	Vtestbench *tb = new Vtestbench;
	
	Verilated::traceEverOn(true);
	VerilatedVcdC* tfp = new VerilatedVcdC;
	tb->trace(tfp, 99);
	tfp->open("testbench_trace.vcd");
	
	tb -> reset = 0;
	for(int i = 0; i < 5; i++){
		tick(++tickcount, tb, tfp);
	}

	tb -> reset = 1;
	tb -> programRunning = 0;
	tb -> programLoader_valid = 0;
	for(int i = 0; i < 8; i++){
		tick(++tickcount, tb, tfp);
	}

	tb -> reset = 0;
	for(int i = 0; i < 5; i++){
		tick(++tickcount, tb, tfp);
	}

	ifstream input("/mnt/AE06F37906F3413F/University/GitHub/cpu-test/add.bin", ios::binary);

	vector<unsigned char> buffer(istreambuf_iterator<char>(input), {});

	tb -> programLoader_valid = 1;
	for (int i = 0; i < buffer.size(); i++) {
		tb -> programLoader_byte = buffer.at(i);
		tick(++tickcount, tb, tfp);
	}
	tb -> programLoader_valid = 0;
	tb -> programRunning = 1;
	for(int i = 0; i < 20; i++){
		tick(++tickcount, tb, tfp);
	}
}

