#include <stdio.h>
#include <stdlib.h>
#include "VmainMemory.h"
#include "verilated.h"
#include "verilated_vcd_c.h"

void tick(int tickcount, VmainMemory *tb, VerilatedVcdC* tfp){
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

struct read_address_beat
{
	__uint8_t arvalid;
	__uint8_t arid;
	__uint8_t arlen;
	__uint8_t arsize;
	__uint32_t araddr;
};


int main(int argc, char **argv){

	unsigned tickcount = 0;

	// Call commandArgs first!
	Verilated::commandArgs(argc, argv);
	
	//Instantiate our design
	VmainMemory *tb = new VmainMemory;
	
	Verilated::traceEverOn(true);
	VerilatedVcdC* tfp = new VerilatedVcdC;
	tb->trace(tfp, 99);
	tfp->open("mainMemory_trace.vcd");

	tb -> reset = 1;
	for(int i = 0; i < 20; i++){
		tick(++tickcount, tb, tfp);
	}
	tb -> reset = 0;
	for(int i = 0; i < 20; i++){
		tick(++tickcount, tb, tfp);
	}

	// programming memory
	tb->programmer_valid = 1;
	for (__uint8_t i = 0; i < 0x10; i++) {
		tb->programmer_offset = i;
		tb->programmer_byte = 0x11U*i;
		tick(++tickcount, tb, tfp);
	}
	tb->programmer_valid=0;
	tick(++tickcount, tb, tfp);

	tb->finishedProgramming = 1; tick(++tickcount, tb, tfp);
	tb->finishedProgramming = 0;
	for(__uint8_t i = 0; i < 5; i++)
		tick(++tickcount, tb, tfp);

	// reading initial programmed memory
	read_address_beat read_requests[] = {
		{1, 0, 1, 2, 0}, {1, 0, 0, 2, 8}, {1, 0, 0, 1, 12}, {1, 0, 0, 0, 14} 
	};
	// sending axi read request
	tb -> clients_1_RREADY = 1;
	for (__uint8_t i = 0; i < 4; i++) {
		tb -> clients_1_ARADDR = read_requests[i].araddr;
		tb -> clients_1_ARID = read_requests[i].arid;
		tb -> clients_1_ARLEN = read_requests[i].arlen;
		tb -> clients_1_ARSIZE = read_requests[i].arsize;
		tb -> clients_1_ARVALID = read_requests[i].arvalid;
		while (!tb->clients_1_ARREADY)
			tick(++tickcount, tb, tfp);
		tick(++tickcount, tb, tfp);
	}
}

