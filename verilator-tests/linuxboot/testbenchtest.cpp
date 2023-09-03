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

//#define TEST_ALL

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
		for(int i = 0; i < 50000; i++){
			if(tb -> uart_valid) 
				printf("%c", tb -> uart_character);
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
		//vvvvv uncomment below to get waveform vvvvvvvv
		tfp->open("linux_trace.vcd");
		/* ifstream infile("bblhex.txt");
		string line;
		while (getline(infile, line)) {            // Initialize memory with instructions
			temp = stoul(line,nullptr,16);
      std::cout << line << endl;
    } */
		
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

		printf("*********************************Loading kernel image*********************************\n");
		ifstream input("bbl.bin", ios::binary);
		//printf("Running test for %s: ", test.c_str());

		//vector<unsigned char> buffer(istreambuf_iterator<char>(input), {});

		//cout << buffer.size() << endl;
		tb -> programLoader_valid = 1;
		int progress = 0;
		//printf("Loading kernel image|                    |\r");
		/* for (int i = 0; i < buffer.size(); i++) {
			tb -> programLoader_byte = buffer.at(i);
			//cout << buffer.at(i)&255 << endl;
			tick(++tickcount, tb, tfp);
			
		} */
		ifstream infile("bblhex.txt");
		string line;
		unsigned long temp;
		while (getline(infile, line)) {            // Initialize memory with instructions
			temp = stoul(line,nullptr,16);
      tb -> programLoader_byte = (unsigned char)temp & 255;
			temp = temp >> 8;
			tick(++tickcount, tb, tfp);
      tb -> programLoader_byte = (unsigned char)temp & 255;
			temp = temp >> 8;
			tick(++tickcount, tb, tfp);
      tb -> programLoader_byte = (unsigned char)temp & 255;
			temp = temp >> 8;
			tick(++tickcount, tb, tfp);
      tb -> programLoader_byte = (unsigned char)temp & 255;
			temp = temp >> 8;
			tick(++tickcount, tb, tfp);
    }
		ofstream MyFile("pc_trace.txt");
		ofstream pcOnly("pc_only.log");
		ofstream pcfiredTime("pc_times.log");

		tb -> programLoader_valid = 0;
		tb -> programRunning = 1;
		printf("*********************************Booting to kernel*********************************\n");
		while(1){
			if(tb -> uart_valid) 
				printf("%c", tb -> uart_character);

			if (tb -> execOut_fired){
				MyFile << std::hex << tb -> execOut_pc << " " << std::hex << tb -> execOut_instruction;
				if ((tb -> execOut_instruction & 124) == 0)
				{
					MyFile << "\t\tread  " << std::hex << ( tb -> execOut_src1 + tb -> execOut_src2 );
				}else if ((tb -> execOut_instruction & 124) == 32)
				{
					MyFile << "\t\tstore " << std::hex << ( tb -> execOut_src1 + tb -> execOut_src2 ) << std::hex << ( tb -> execOut_writeData );
				}
				MyFile << std::endl;
				
			}

			if (tb -> fetchOut_fired){
				pcOnly << std::hex << tb -> fetchOut_pc << endl;
				pcfiredTime << std::hex << tb -> fetchOut_pc << " " << tickcount*10 << endl;
			}
			
			tick(++tickcount, tb, tfp);
		}
		if (tb -> results_result == 0)
			printf("Timeout\n");
	#endif
}

