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

#define TEST_ALL

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

	#ifdef TEST_ALL

	//vector<string> tests;
	ifstream infile("tests.txt");
	
	string test;

	while (getline(infile, test)) {
		//Instantiate our design
		Vtestbench *tb = new Vtestbench;
		
		Verilated::traceEverOn(true);
		VerilatedVcdC* tfp = new VerilatedVcdC;
		tb->trace(tfp, 99);
		tfp->open(("waveforms/" + test + ".vcd").c_str());
		
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

		ifstream input(("target_texts/" + test + ".bin"), ios::binary);
		printf("Running test for %s: ", test.c_str());

		vector<unsigned char> buffer(istreambuf_iterator<char>(input), {});

		//cout << buffer.size() << endl;
		tb -> programLoader_valid = 1;
		for (int i = 0; i < buffer.size(); i++) {
			tb -> programLoader_byte = buffer.at(i);
			//cout << buffer.at(i)&255 << endl;
			tick(++tickcount, tb, tfp);
		}
		tb -> programLoader_valid = 0;
		tb -> programRunning = 1;
		for(int i = 0; i < 5000; i++){
			if(tb -> programResult_valid) {
				if(tb -> programResult_result == 1) {
					printf("Test successful \n");
				}else {
					printf("Program failed at: %d \n", (tb -> results_result) >> 1);
				}
				// finishing the last store operation
				/* for (int i = 0; i < 100; i++) {
					tick(++tickcount, tb, tfp);
				} */
				break;
			}
			tick(++tickcount, tb, tfp);
		}
		if (tb -> programResult_valid == 0)
			printf("Timeout\n");
	}
	#endif

	#ifndef TEST_ALL
		Vtestbench *tb = new Vtestbench;
		
		Verilated::traceEverOn(true);
		VerilatedVcdC* tfp = new VerilatedVcdC;
		tb->trace(tfp, 99);
		tfp->open("add_trace.vcd");
		
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

		ifstream input("add.text", ios::binary);
		//printf("Running test for %s: ", test.c_str());

		vector<unsigned char> buffer(istreambuf_iterator<char>(input), {});

		//cout << buffer.size() << endl;
		tb -> programLoader_valid = 1;
		for (int i = 0; i < buffer.size(); i++) {
			tb -> programLoader_byte = buffer.at(i);
			//cout << buffer.at(i)&255 << endl;
			tick(++tickcount, tb, tfp);
		}
		tb -> programLoader_valid = 0;
		tb -> programRunning = 1;
		for(int i = 0; i < 5000; i++){
			if(tb -> programResult_valid) {
				if(tb -> programResult_result == 1) {
					printf("Test successful \n");
				}else {
					printf("Program failed at: %d \n", (tb -> results_result) >> 1);
				}
				// finishing the last store operation
				for (int i = 0; i < 100; i++) {
					tick(++tickcount, tb, tfp);
				}
				return 0;
				break;
			}
			tick(++tickcount, tb, tfp);
		}
		if (tb -> results_result == 0)
			printf("Timeout\n");
	#endif
}

