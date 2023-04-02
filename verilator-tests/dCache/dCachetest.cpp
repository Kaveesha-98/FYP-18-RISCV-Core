#include <stdio.h>
#include <stdlib.h>
#include "VdCache.h"
#include "verilated.h"
#include "verilated_vcd_c.h"
#include <stdint.h>

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

	// int read_blocks_to_be_sent = 0;
	u_int32_t read_address = 0;

	
	tb -> reset = 0;
	for(int i = 0; i < 5; i++){
		tick(++tickcount, tb, tfp);
	}
	
	tb -> reset = 1;
	for(int i = 0; i < 5; i++){
		tick(++tickcount, tb, tfp);
	}
	
	tb -> reset = 0;
	for(int i = 0; i < 5; i++){
		tick(++tickcount, tb, tfp);
	}

	u_int8_t read_blocks_to_be_sent;

	
	tb -> pipelineMemAccess_req_bits_address = 0x80000000;
	tb -> pipelineMemAccess_req_bits_instruction = 0x0000b703;
	tb -> pipelineMemAccess_req_bits_robAddr = 5;

	//==============================Compulsory misses========================================================
	printf("***********************************************\n");
	printf("Filling cache with contigous memory\n");
	for (int i = 0; i < (1 << 6); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		// printf("Waiting for request to be sent\n");
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;
		tb -> lowLevelAXI_ARREADY = 1;

		// printf("Waiting for a read request on AXI\n");
		while (tb -> lowLevelAXI_ARVALID == 0)
			tick(++tickcount, tb, tfp);

		//printf("Block requested: %x\n", tb -> lowLevelAXI_ARADDR);
		if ((u_int64_t) tb -> lowLevelAXI_ARADDR != (u_int64_t) tb -> pipelineMemAccess_req_bits_address){
			printf("Incorrect block %x requested for original block request %lx\n", tb -> lowLevelAXI_ARADDR, tb -> pipelineMemAccess_req_bits_address);
			return 1;
		}


		read_blocks_to_be_sent = tb -> lowLevelAXI_ARLEN + 1;
		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_ARREADY = 0;
		tb -> lowLevelAXI_RVALID = 1;
		tb -> lowLevelAXI_RDATA = 0xAAAAAAAA;

		// printf("sending the read blocks\n");
		while (read_blocks_to_be_sent > 0) {
			tb -> lowLevelAXI_RLAST = (read_blocks_to_be_sent == 1);
			if(tb -> lowLevelAXI_RREADY == 1)
				read_blocks_to_be_sent--;

			tick(++tickcount, tb, tfp);
		}
		tb -> lowLevelAXI_RLAST = 0;
		tb -> lowLevelAXI_RVALID = 0;

		tb -> pipelineMemAccess_resp_ready = 1;
		// printf("waiting for a response\n");
		while (tb -> pipelineMemAccess_resp_valid == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address += (1 << 6);
	}
	printf("Cache filled\n");
	
	//=============================================ALWAYS HIT========================================================
	printf("***********************************************\n");
	printf("Reading back all cache lines, no cache misses should happen\n");
	tb -> pipelineMemAccess_req_bits_instruction = 0x00008703;
	tb -> pipelineMemAccess_req_bits_address = 0x80000000;
	for (int i = 0; i < (1 << 12); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		//printf("Waiting for request to be sent\n");
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;

		tb -> pipelineMemAccess_resp_ready = 1;
		while (tb -> pipelineMemAccess_resp_valid == 0){
			if (tb -> lowLevelAXI_ARVALID == 1) {
				printf("A miss occured\n");
				return 1;
			} 
			tick(++tickcount, tb, tfp);
		}

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address += 1;
	}
	printf("No cache misses occured\n");

	//=================================================CONFLICT MISSES==================================================
	printf("***********************************************\n");
	printf("Filling cache with contigous memory\n");
	tb -> pipelineMemAccess_req_bits_instruction = 0x0000b703;
	tb -> pipelineMemAccess_req_bits_address = 0x80001fc0;
	for (int i = 0; i < (1 << 6); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		//printf("Waiting for request to be sent\n");
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;
		tb -> lowLevelAXI_ARREADY = 1;

		//printf("Waiting for a read request on AXI\n");
		while (tb -> lowLevelAXI_ARVALID == 0)
			tick(++tickcount, tb, tfp);

		//printf("Block requested: %x\n", tb -> lowLevelAXI_ARADDR);
		if ((u_int64_t) tb -> lowLevelAXI_ARADDR != (u_int64_t) tb -> pipelineMemAccess_req_bits_address){
			printf("Incorrect block %x requested for original block request %lx\n", tb -> lowLevelAXI_ARADDR, tb -> pipelineMemAccess_req_bits_address);
			return 1;
		}


		read_blocks_to_be_sent = tb -> lowLevelAXI_ARLEN + 1;
		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_ARREADY = 0;
		tb -> lowLevelAXI_RVALID = 1;
		tb -> lowLevelAXI_RDATA = 0x55555555;

		while (read_blocks_to_be_sent > 0) {
			tb -> lowLevelAXI_RLAST = (read_blocks_to_be_sent == 1);
			if(tb -> lowLevelAXI_RREADY == 1)
				read_blocks_to_be_sent--;

			tick(++tickcount, tb, tfp);
		}
		tb -> lowLevelAXI_RLAST = 0;
		tb -> lowLevelAXI_RVALID = 0;

		tb -> pipelineMemAccess_resp_ready = 1;
		while (tb -> pipelineMemAccess_resp_valid == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address -= (1 << 6);
	}
	printf("Cache re-filled\n");

	//=============================================ALWAYS HIT========================================================
	printf("***********************************************\n");
	printf("Reading back all cache lines, no cache misses should happen\n");
	tb -> pipelineMemAccess_req_bits_instruction = 0x00008703;
	tb -> pipelineMemAccess_req_bits_address = 0x80001fff;
	for (int i = 0; i < (1 << 12); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		//printf("Waiting for request to be sent\n");
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;

		tb -> pipelineMemAccess_resp_ready = 1;
		while (tb -> pipelineMemAccess_resp_valid == 0){
			if (tb -> lowLevelAXI_ARVALID == 1) {
				printf("A miss occured\n");
				return 1;
			} 
			tick(++tickcount, tb, tfp);
		}

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address -= 1;
	}
	printf("No cache misses occured\n");

	//=============================================ALWAYS HIT WRITES========================================================
	printf("***********************************************\n");
	printf("Writing to cache lines(byte wise)\n");
	tb -> pipelineMemAccess_req_bits_instruction = 0x00008723;
	tb -> pipelineMemAccess_req_bits_address = 0x80001fff;
	tb -> pipelineMemAccess_req_bits_writeData = 0x11111111;
	for (int i = 0; i < (1 << 12); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		//printf("1\n");
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		/* if(wait_count == 0)
			return -1; */

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;
		tb -> lowLevelAXI_AWREADY = 1;

		//printf("2\n");
		while (tb -> lowLevelAXI_AWVALID == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_WREADY = 1;
		tb -> lowLevelAXI_AWREADY = 0;
		//printf("3\n");
		while (!(tb -> lowLevelAXI_WVALID && tb -> lowLevelAXI_WLAST))
			tick(++tickcount, tb, tfp);
		//return -1;

		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_WREADY = 0;
		tb -> lowLevelAXI_BVALID = 1;
		//printf("4\n");
		while (tb -> lowLevelAXI_BREADY == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_BVALID = 0;
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address -= 1;
	}
	printf("Finished writing to all cache lines\n");

	//=============================================WRITES CHECK========================================================
	printf("***********************************************\n");
	printf("Reading back all cache lines, no cache misses should happen\n");
	tb -> pipelineMemAccess_req_bits_instruction = 0x00008703;
	tb -> pipelineMemAccess_req_bits_address = 0x80001000;
	for (int i = 0; i < (1 << 12); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		// printf("Waiting for request to be sent\n");
		/* for (int j = 0; j < 10; j++)
			tick(++tickcount, tb, tfp);

		return -1; */
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;

		tb -> pipelineMemAccess_resp_ready = 1;
		// printf("Waiting for response\n");
		
		/* for (int j = 0; j < 10; j++)
			tick(++tickcount, tb, tfp);

		return -1; */
		while (tb -> pipelineMemAccess_resp_valid == 0){
			if (tb -> lowLevelAXI_AWVALID == 1) {
				printf("A miss occured\n");
				return 1;
			} 
			tick(++tickcount, tb, tfp);
		}
		if(tb -> pipelineMemAccess_resp_bits_byteAlignedData != 0x1111111111111111) {
			printf("Writes not properly executed\n");
			return -1;
		}

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address += 1;
	}
	printf("No cache misses occured\n");

	//==============================Cache Miss Writes========================================================
	printf("***********************************************\n");
	printf("Writes that miss on cache\n");
	tb -> pipelineMemAccess_req_bits_address = 0x80000000;
	tb -> pipelineMemAccess_req_bits_instruction = 0x0000b723;
	tb -> pipelineMemAccess_req_bits_robAddr = 5;
	for (int i = 0; i < (1 << 6); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		// printf("Waiting for request to be sent\n");
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;
		tb -> lowLevelAXI_ARREADY = 1;

		// printf("Waiting for a read request on AXI\n");
		while (tb -> lowLevelAXI_ARVALID == 0)
			tick(++tickcount, tb, tfp);

		//printf("Block requested: %x\n", tb -> lowLevelAXI_ARADDR);
		if ((u_int64_t) tb -> lowLevelAXI_ARADDR != (u_int64_t) tb -> pipelineMemAccess_req_bits_address){
			printf("Incorrect block %x requested for original block request %lx\n", tb -> lowLevelAXI_ARADDR, tb -> pipelineMemAccess_req_bits_address);
			return 1;
		}


		read_blocks_to_be_sent = tb -> lowLevelAXI_ARLEN + 1;
		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_ARREADY = 0;
		tb -> lowLevelAXI_RVALID = 1;
		tb -> lowLevelAXI_RDATA = 0xAAAAAAAA;

		// printf("sending the read blocks\n");
		while (read_blocks_to_be_sent > 0) {
			tb -> lowLevelAXI_RLAST = (read_blocks_to_be_sent == 1);
			if(tb -> lowLevelAXI_RREADY == 1)
				read_blocks_to_be_sent--;

			tick(++tickcount, tb, tfp);
		}
		tb -> lowLevelAXI_RLAST = 0;
		tb -> lowLevelAXI_RVALID = 0;

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;
		tb -> lowLevelAXI_AWREADY = 1;

		//printf("2\n");
		while (tb -> lowLevelAXI_AWVALID == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_WREADY = 1;
		tb -> lowLevelAXI_AWREADY = 0;
		//printf("3\n");
		while (!(tb -> lowLevelAXI_WVALID && tb -> lowLevelAXI_WLAST))
			tick(++tickcount, tb, tfp);
		//return -1;

		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_WREADY = 0;
		tb -> lowLevelAXI_BVALID = 1;
		//printf("4\n");
		while (tb -> lowLevelAXI_BREADY == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> lowLevelAXI_BVALID = 0;
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address += (1 << 6);
	}
	printf("Cache filled\n");

	//=============================================WRITES CHECK========================================================
	printf("***********************************************\n");
	printf("Reading back all written words, no cache misses should happen\n");
	tb -> pipelineMemAccess_req_bits_instruction = 0x00008703;
	tb -> pipelineMemAccess_req_bits_address = 0x80000000;
	for (int i = 0; i < (1 << 6); i++){

		tb -> pipelineMemAccess_req_valid = 1;
		// printf("Waiting for request to be sent\n");
		/* for (int j = 0; j < 10; j++)
			tick(++tickcount, tb, tfp);

		return -1; */
		while (tb -> pipelineMemAccess_req_ready == 0)
			tick(++tickcount, tb, tfp);

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_req_valid = 0;

		tb -> pipelineMemAccess_resp_ready = 1;
		// printf("Waiting for response\n");
		
		/* for (int j = 0; j < 10; j++)
			tick(++tickcount, tb, tfp);

		return -1; */
		while (tb -> pipelineMemAccess_resp_valid == 0){
			if (tb -> lowLevelAXI_AWVALID == 1) {
				printf("A miss occured\n");
				return 1;
			} 
			tick(++tickcount, tb, tfp);
		}
		if(tb -> pipelineMemAccess_resp_bits_byteAlignedData != 0x0000000011111111) {
			printf("Writes not properly executed\n");
			return -1;
		}

		tick(++tickcount, tb, tfp);
		tb -> pipelineMemAccess_resp_ready = 0;
		tb -> pipelineMemAccess_req_bits_address += (1 << 6);
	}
	printf("No cache misses occured\n");
}

