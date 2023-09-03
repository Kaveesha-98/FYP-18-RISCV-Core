module dCacheRegisters #(
  parameter double_word_offset_width = 3, // 2^double_word_offset_width of double words per block
  parameter line_width = 6, // 2^line_wdith of cache lines per set
  localparam tag_width = 32 - double_word_offset_width -3 - line_width, //  
  localparam cache_depth = 1 << line_width,
  localparam block_size = 1 << double_word_offset_width // double words per block
) (
  input [31:0] address,
  output reg [63:0] byte_aligned_data,
  output reg [tag_width-1: 0] tag,
  output reg tag_valid,
  input [line_width-1:0] write_line_index,
  input [64*block_size - 1:0] write_block,
  input [tag_width-1: 0] write_tag,
  input [block_size-1:0] write_mask,
  input reset, write_in, clock
);

  reg [63:0] cache [cache_depth-1:0][block_size-1:0];
  reg [tag_width-1:0] tags [cache_depth-1:0];
  reg validBits [cache_depth-1:0];

  always @(posedge clock) begin
    byte_aligned_data <= cache[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]][address[double_word_offset_width-1 + 3:3]];
    tag <= tags[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]];
    tag_valid <= validBits[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]];
  end 
  /* assign byte_aligned_data = cache[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]][address[double_word_offset_width-1 + 3:3]];
  assign tag = tags[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]];
  assign tag_valid = validBits[address[line_width+double_word_offset_width+3-1:double_word_offset_width+3]]; */

  always @(posedge clock) begin
    if (reset) begin
      for (integer i = 0; i < cache_depth; i++) begin
        validBits[i] <= 1'b0;
      end
    end else if (write_in) begin
      // partial writes only occur for blocks already in cache
      for (integer j = 0; j < block_size; j++) begin
        if (write_mask[j])begin
          cache[write_line_index][j] <= write_block[64*j +: 64];
        end
      end
      validBits[write_line_index] <= 1'b1;
      tags[write_line_index] <= write_tag;
    end
  end

endmodule