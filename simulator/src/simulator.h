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

#define STEP_TIMEOUT 1000

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

  __uint64_t get_register_value(__uint8_t rd) {
    switch (rd) {
    case 0:
      return tb -> registersOut_0;
    case 1:
      return tb -> registersOut_1;
    case 2:
      return tb -> registersOut_2;
    case 3:
      return tb -> registersOut_3;
    case 4:
      return tb -> registersOut_4;
    case 5:
      return tb -> registersOut_5;
    case 6:
      return tb -> registersOut_6;
    case 7:
      return tb -> registersOut_7;
    case 8:
      return tb -> registersOut_8;
    case 9:
      return tb -> registersOut_9;
    case 10:
      return tb -> registersOut_10;
    case 11:
      return tb -> registersOut_11;
    case 12:
      return tb -> registersOut_12;
    case 13:
      return tb -> registersOut_13;
    case 14:
      return tb -> registersOut_14;
    case 15:
      return tb -> registersOut_15;
    case 16:
      return tb -> registersOut_16;
    case 17:
      return tb -> registersOut_17;
    case 18:
      return tb -> registersOut_18;
    case 19:
      return tb -> registersOut_19;
    case 20:
      return tb -> registersOut_20;
    case 21:
      return tb -> registersOut_21;
    case 22:
      return tb -> registersOut_22;
    case 23:
      return tb -> registersOut_23;
    case 24:
      return tb -> registersOut_24;
    case 25:
      return tb -> registersOut_25;
    case 26:
      return tb -> registersOut_26;
    case 27:
      return tb -> registersOut_27;
    case 28:
      return tb -> registersOut_28;
    case 29:
      return tb -> registersOut_29;
    case 30:
      return tb -> registersOut_30;
    case 31:
      return tb -> registersOut_31;
    default:
      return 0;
    }
  }

  void init(
    std::string image_name = "Image",
    std::string dtb_name = "qemu.dtb",
    std::string boot_rom = "boot.bin"
  ) {
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
    printf("loading dtb\n");
    ifstream dtb_input(dtb_name, ios::binary);
		//printf("Running test for : ");

		vector<unsigned char> dtb_buffer(istreambuf_iterator<char>(dtb_input), {});
    // int next_step = buffer.size()/20;
		//printf("Loading kernel image|                    |");
		for (int i = 0; i < dtb_buffer.size(); i=i+8) {
			tb -> programmer_byte = *reinterpret_cast<unsigned long*>(&dtb_buffer.at(i));
      tb -> programmer_offset = (i+0x07e00000UL);
			//cout << buffer.at(i)&255 << endl;
			tick_nodump(++tickcount, tb, tfp);	
      // if (progress != (i*100)/buffer.size()) 
      printf("Kernel Loaded: %ld \%\r", (i*100)/buffer.size());		
		}
    printf("done\n");
    printf("loading boot rom\n");
    ifstream boot_input(boot_rom, ios::binary);
		//printf("Running test for : ");

		vector<unsigned char> boot_buffer(istreambuf_iterator<char>(boot_input), {});
    // int next_step = buffer.size()/20;
		//printf("Loading kernel image|                    |");
		for (int i = 0; i < boot_buffer.size(); i=i+8) {
			tb -> programmer_byte = *reinterpret_cast<unsigned long*>(&boot_buffer.at(i));
      tb -> programmer_offset = (i+0x07ffff00UL);
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
    #ifdef SHOW_TERMINAL
      if (tb ->putChar_valid) { cout << tb -> putChar_byte << flush; }
    #endif
      tick(++tickcount, tb, tfp);
    }
    
    #ifdef SHOW_TERMINAL
    if (tb ->putChar_valid) { cout << tb -> putChar_byte << flush; }
    #endif
    // return 1 indicate timeout
    prev_pc = tb -> robOut_pc;
    if (tb -> robOut_commitFired) { return 0; } else { printf("TIMEOUT IN SIMULATOR!!!\n"); return 1; }
  }

  int step_nodump() {
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
    tick_nodump(++tickcount, tb, tfp);
    #ifndef STEP_TIMEOUT
    while (!(tb -> robOut_commitFired)) {
    #else
    for (int i = 0; !(tb -> robOut_commitFired) && i < STEP_TIMEOUT; i++) {
    #endif
    #ifdef SHOW_TERMINAL
      //if (tb ->putChar_valid) { cout << tb -> putChar_byte << flush; }
    #endif
      tick_nodump(++tickcount, tb, tfp);
    }
    
    #ifdef SHOW_TERMINAL
    //if (tb ->putChar_valid) { cout << tb -> putChar_byte << flush; }
    #endif
    // return 1 indicate timeout
    prev_pc = tb -> robOut_pc;
    if (tb -> robOut_commitFired) { return 0; } else { printf("TIMEOUT IN SIMULATOR!!!\n"); return 1; }
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