module dCacheRegisters #(
  parameter double_word_offset_width = 3, // 2^double_word_offset_width of double words per block
  parameter line_width = 6, // 2^line_wdith of cache lines per set
  localparam tag_width = 32 - offset_width -3 - line_wdith, //  
  localparam cache_depth = 1 << line_wdith,
  localparam block_size = 1 << offset_width // double words per block
) (
  input [31:0] address,
  output [63:0] byte_aligned_data,
  output [tag_width-1: 0] tag,
  output tag_valid,
  input [line_width-1:0] write_line_index,
  input [64*block_size - 1:0] write_block,
  input [tag_width-1, 0] write_tag,
  input [63:0] store_data,
  input [31:0] store_address,
  input reset, write_in, clock, commit_store
);

  reg [63:0] cache [cache_depth-1:0][block_size-1:0];
  reg [tag_width-1:0] tags [cache_depth-1:0];
  reg validBits [cache_depth-1:0];

  assign byte_aligned_data = cache[address[line_wdith+offset_width+3-1:offset_width+3]][address[offset_width-1 + 3:3]];
  assign tag = tags[address[line_wdith+offset_width+3-1:offset_width+3]];
  assign tag_valid = validBits[address[line_wdith+offset_width+3-1:offset_width+3]]

  genvar i, j;
  always @(posedge clock) begin
    if (reset) begin
      for (i = 0; i < cache_depth; i++) begin
        validBits[i] <= 1'b0;
      end
    end else if (write_in) begin
      for (j = 0; j < block_size; j++) begin
        cache[write_line_index][j] <= write_block[64*(j+1) - 1: 64*j];
      end
      validBits[write_line_index] <= 1'b1;
      tags[write_line_index] <= write_tag;
    end else if(commit_store) begin
      cache[store_address[line_wdith+offset_width+3-1:offset_width+3]][store_address[offset_width-1 + 3:3]] <= store_data;
    end
  end

endmodule