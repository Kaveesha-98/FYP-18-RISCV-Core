module iCacheRegisters #(
  parameter offset_width = 2, // 2^offset_width of instructions per block
  parameter line_width = 6, // 2^line_width of cache lines per set
  localparam tag_width = 32 - offset_width - line_width - 2, //  
  localparam cache_depth = 1 << line_width,
  localparam block_size = 1 << offset_width
) (
  input [31:0] address,
  output reg [31:0] instruction,
  output reg [tag_width-1: 0] tag,
  output reg tag_valid,
  input [line_width-1:0] write_line_index,
  input [32*block_size - 1:0] write_block,
  input [tag_width-1: 0] write_tag,
  input reset, write_in, clock, invalidate_all
);

  reg [31:0] cache [cache_depth-1:0][block_size-1:0];
  reg [tag_width-1:0] tags [cache_depth-1:0];
  reg validBits [cache_depth-1:0];

  always@(posedge clock) begin
    instruction <= cache[address[line_width+offset_width+2-1:offset_width+2]][address[offset_width+2-1:2]];
    tag <= tags[address[line_width+offset_width+2-1:offset_width+2]];
    tag_valid <= validBits[address[line_width+offset_width+2-1:offset_width+2]];
  end

  /* assign instruction = cache[address[line_width+offset_width+2-1:offset_width+2]][address[offset_width+2-1:2]];
  assign tag = tags[address[line_width+offset_width+2-1:offset_width+2]]; */
  // assign tag_valid = validBits[address[line_width+offset_width+2-1:offset_width+2]];
  integer i, j;
  always @(posedge clock) begin
    if (reset || invalidate_all) begin
      for (i = 0; i < cache_depth; i=i+1) begin
        validBits[i] <= 1'b0;
      end
    end else if (write_in) begin
      for (j = 0; j < block_size; j=j+1) begin
        cache[write_line_index][j] <= write_block[32*j +: 32];
      end
      validBits[write_line_index] <= 1'b1;
      tags[write_line_index] <= write_tag;
    end
  end

endmodule