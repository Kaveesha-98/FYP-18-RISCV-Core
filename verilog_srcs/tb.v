`timescale 1ns / 1ps
//////////////////////////////////////////////////////////////////////////////////
// Company: 
// Engineer: 
// 
// Create Date: 04/10/2023 09:10:21 AM
// Design Name: 
// Module Name: tb
// Project Name: 
// Target Devices: 
// Tool Versions: 
// Description: 
// 
// Dependencies: 
// 
// Revision:
// Revision 0.01 - File Created
// Additional Comments:
// 
//////////////////////////////////////////////////////////////////////////////////


module tb();
    // clock and resets
    reg clock, reset;
    reg        programLoader_valid;
    //wire  [7:0] programLoader_byte;
    reg        programRunning;
    wire       results_valid;
    wire [7:0] results_result;
    
    reg [7:0] mem [0:1930];
    
    initial begin
        $readmemh("/mnt/AE06F37906F3413F/University/GitHub/FYP-18-RISCV-Core/verilator-tests/testbench/output.hex", mem); // read bytes from file and store in register array
    end
    
    reg [11:0] mem_index;
    wire [7:0] programLoader_byte = mem[mem_index];
    
    initial begin
        clock = 1'b0;
        forever #5 clock = ~clock;
    end
    
    testbench testbench_0(
        .clock(clock),
        .reset(reset),
        .programLoader_valid(programLoader_valid),
        .programLoader_byte(programLoader_byte),
        .programRunning(programRunning),
        .results_valid(results_valid),
        .results_result(results_result)
    );
    
    initial begin
        $dumpfile("dump.vcd"); $dumpvars(0, testbench_0);
        reset = 1'b0;
        programLoader_valid = 1'b0;
        mem_index = 12'd0;
        programRunning = 1'b0;
        repeat(5)@(posedge clock);
        #5 reset = 1'b1;
        repeat(8)@(posedge clock);
        #5 reset = 1'b0;
        repeat(1)@(posedge clock);
        #5 programLoader_valid = 1'b1;
        repeat(1930) #10 mem_index = mem_index + 1;
        #5 programLoader_valid = 1'b0;
        repeat(5)@(posedge clock);
        #1 programRunning = 1'b1;
        repeat(5000)@(posedge clock);
        #1 programRunning = 1'b0;
    end
endmodule
