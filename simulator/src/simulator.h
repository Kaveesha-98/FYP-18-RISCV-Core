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

using namespace std;

class simulator {
  private:
  Vsystem *tb;
	VerilatedVcdC* tfp;
  unsigned tickcount;
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
  void init() {
    tb = (new Vsystem);
	
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
		ifstream input("Image", ios::binary);
		//printf("Running test for : ");

		vector<unsigned char> buffer(istreambuf_iterator<char>(input), {});

		//cout << buffer.size() << endl;
		tb ->programmer_valid = 1;
		int progress = 0;
    int next_step = buffer.size()/20;
		//printf("Loading kernel image|                    |");
		for (int i = 0; i < buffer.size(); i++) {
			tb -> programmer_byte = buffer.at(i);
      tb -> programmer_offset = i;
			//cout << buffer.at(i)&255 << endl;
			tick_nodump(++tickcount, tb, tfp);			
		}
		tb ->finishedProgramming = 1;
    tb ->programmer_valid = 0;
    tick_nodump(++tickcount, tb, tfp);
		tb ->finishedProgramming = 0;
    tb ->programmer_valid = 0;
    tick_nodump(++tickcount, tb, tfp);

  }

  void step() {
    while (1) { // runs until a instruction is completed
      if (tb -> robOut_commitFired){
        tick(++tickcount, tb, tfp);
        if (tb ->putChar_valid) { printf("%c", tb-> putChar_byte&0xff); }
        break;
      }
      
      tick(++tickcount, tb, tfp);

      if (tb ->putChar_valid) { printf("%c", tb-> putChar_byte&0xff); }
    }
    
  }
};