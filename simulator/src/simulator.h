#include <stdio.h>
#include <stdlib.h>
#include "Vsystem.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <fstream>
#include <iterator>
#include <vector>
#include <iostream>
#include <string>
#include <stdint.h>

#define STEP_TIMEOUT 100

using namespace std;

class simulator {
  private:
  Vsystem         *tb;
	VerilatedVcdC*  tfp;

  void tick(int tickcount, Vsystem *tb, VerilatedVcdC* tfp){
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

  void tick_nodump(int tickcount, Vsystem *tb, VerilatedVcdC* tfp){
    tb->eval();
    tb->clock = 1;
    tb->eval();
    tb->clock = 0;
    tb->eval();
  }

  public:

  uint64_t  prev_pc;
  unsigned        tickcount;

  void init(std::string image_name = "Image") {
    tb = (new Vsystem);
    tickcount = 0UL;
	
    Verilated::traceEverOn(true);
    tfp = new VerilatedVcdC;
    tb->trace(tfp, 99);
    tfp->open("system_trace.vcd");

    tb -> reset = 1;
    for(int i = 0; i < 20; i++){
      tick_nodump(++tickcount, tb, tfp);
    }
    tb -> reset = 0;
    for(int i = 0; i < 20; i++){
      tick_nodump(++tickcount, tb, tfp);
    }

    printf("*********************************Loading kernel image*********************************\n");
		ifstream input(image_name, ios::binary);
		//printf("Running test for : ");

		vector<unsigned char> buffer(istreambuf_iterator<char>(input), {});

		//cout << buffer.size() << endl;
		tb ->programmer_valid = 1;
		unsigned long progress = 0UL;
    int next_step = buffer.size()/20;
		//printf("Loading kernel image|                    |");
		for (int i = 0; i < buffer.size(); i=i+8) {
			tb -> programmer_byte = *reinterpret_cast<unsigned long*>(&buffer.at(i));
      tb -> programmer_offset = i;
			//cout << buffer.at(i)&255 << endl;
			tick_nodump(++tickcount, tb, tfp);	
      // if (progress != (i*100)/buffer.size()) 
      printf("Kernel Loaded: %ld \%\r", (i*100)/buffer.size());		
		}
    printf("done\n");
		tb ->finishedProgramming = 1;
    tb ->programmer_valid = 0;
    tick_nodump(++tickcount, tb, tfp);
		tb ->finishedProgramming = 0;
    tb ->programmer_valid = 0;
    tick_nodump(++tickcount, tb, tfp);
    prev_pc = 0x80000000UL;
  }

  int step() {
    /* while (1) { // runs until a instruction is completed
      if (tb -> robOut_commitFired){
        prev_pc = tb -> robOut_pc;
        tick_nodump(++tickcount, tb, tfp);
        if (tb ->putChar_valid) { cout << (char)(tb -> putChar_byte) << flush; }
        break;
      }
      
      tick_nodump(++tickcount, tb, tfp);

      if (tb ->putChar_valid) { cout << (char)(tb -> putChar_byte) << flush; }
    } */
    tick(++tickcount, tb, tfp);
    #ifndef STEP_TIMEOUT
    while (!(tb -> robOut_commitFired)) {
    #else
    for (int i = 0; !(tb -> robOut_commitFired) && i < STEP_TIMEOUT; i++) {
    #endif
      // if (tb ->putChar_valid) { cout << (char)(tb -> putChar_byte) << flush; }
      tick(++tickcount, tb, tfp);
    }
    // return 1 indicate timeout
    prev_pc = tb -> robOut_pc;
    if (tb -> robOut_commitFired) { return 0; } else { return 1; }
  }

  int check_registers(unsigned long *correct) {
    if ( tb -> registersOut_1 != correct[1] ) { return 1; }
    if ( tb -> registersOut_2 != correct[2] ) { return 2; }
    if ( tb -> registersOut_3 != correct[3] ) { return 3; }
    if ( tb -> registersOut_4 != correct[4] ) { return 4; }
    if ( tb -> registersOut_5 != correct[5] ) { return 5; }
    if ( tb -> registersOut_6 != correct[6] ) { return 6; }
    if ( tb -> registersOut_7 != correct[7] ) { return 7; }
    if ( tb -> registersOut_8 != correct[8] ) { return 8; }
    if ( tb -> registersOut_9 != correct[9] ) { return 9; }
    if ( tb -> registersOut_10 != correct[10] ) { return 10; }
    if ( tb -> registersOut_11 != correct[11] ) { return 11; }
    if ( tb -> registersOut_12 != correct[12] ) { return 12; }
    if ( tb -> registersOut_13 != correct[13] ) { return 13; }
    if ( tb -> registersOut_14 != correct[14] ) { return 14; }
    if ( tb -> registersOut_15 != correct[15] ) { cout << correct[15] << " " << tb -> registersOut_15 << endl; return 15; }
    if ( tb -> registersOut_16 != correct[16] ) { return 16; }
    if ( tb -> registersOut_17 != correct[17] ) { return 17; }
    if ( tb -> registersOut_18 != correct[18] ) { return 18; }
    if ( tb -> registersOut_19 != correct[19] ) { return 19; }
    if ( tb -> registersOut_20 != correct[20] ) { return 20; }
    if ( tb -> registersOut_21 != correct[21] ) { return 21; }
    if ( tb -> registersOut_22 != correct[22] ) { return 22; }
    if ( tb -> registersOut_23 != correct[23] ) { return 23; }
    if ( tb -> registersOut_24 != correct[24] ) { return 24; }
    if ( tb -> registersOut_25 != correct[25] ) { return 25; }
    if ( tb -> registersOut_26 != correct[26] ) { return 26; }
    if ( tb -> registersOut_27 != correct[27] ) { return 27; }
    if ( tb -> registersOut_28 != correct[28] ) { return 28; }
    if ( tb -> registersOut_29 != correct[29] ) { return 29; }
    if ( tb -> registersOut_30 != correct[30] ) { return 30; }
    if ( tb -> registersOut_31 != correct[31] ) { return 31; }
    return 0;
  }

  void set_probe(unsigned long address) { tb -> prober_offset = address; }
  unsigned long get_probe() { return tb -> prober_accessLong; }
};