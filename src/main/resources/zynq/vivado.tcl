#-----------------------------------------------------------
# Vivado v2019.2 (64-bit)
# SW Build 2708876 on Wed Nov  6 21:39:14 MST 2019
# IP Build 2700528 on Thu Nov  7 00:09:20 MST 2019
# Start of session at: Mon Dec 25 17:25:22 2023
# Process ID: 16140
# Current directory: /home/kaveesha/Documents/github/FYP-18-RISCV-Core
# Command line: vivado
# Log file: /home/kaveesha/Documents/github/FYP-18-RISCV-Core/vivado.log
# Journal file: /home/kaveesha/Documents/github/FYP-18-RISCV-Core/vivado.jou
#-----------------------------------------------------------
start_gui
create_project vivado vivado -part xc7z020clg400-1
set_property board_part digilentinc.com:zybo-z7-20:part0:1.2 [current_project]
add_files -norecurse {/home/kaveesha/Documents/github/FYP-18-RISCV-Core/psClint.v /home/kaveesha/Documents/github/FYP-18-RISCV-Core/bootROM.v /home/kaveesha/Documents/github/FYP-18-RISCV-Core/core.v}
update_compile_order -fileset sources_1
update_compile_order -fileset sources_1
import_files -norecurse {/home/kaveesha/Documents/github/FYP-18-RISCV-Core/src/main/resources/zynq/iCacheRegisters.v /home/kaveesha/Documents/github/FYP-18-RISCV-Core/src/main/resources/zynq/dCacheRegisters.v}
update_compile_order -fileset sources_1
create_ip -name blk_mem_gen -vendor xilinx.com -library ip -version 8.4 -module_name blk_d_cache
set_property -dict [list CONFIG.Component_Name {blk_d_cache} CONFIG.Memory_Type {Simple_Dual_Port_RAM} CONFIG.Use_Byte_Write_Enable {true} CONFIG.Byte_Size {8} CONFIG.Write_Width_A {512} CONFIG.Write_Depth_A {64} CONFIG.Read_Width_A {512} CONFIG.Operating_Mode_A {NO_CHANGE} CONFIG.Enable_A {Always_Enabled} CONFIG.Write_Width_B {128} CONFIG.Read_Width_B {128} CONFIG.Enable_B {Always_Enabled} CONFIG.Register_PortA_Output_of_Memory_Primitives {false} CONFIG.Register_PortB_Output_of_Memory_Primitives {false} CONFIG.Port_B_Clock {100} CONFIG.Port_B_Enable_Rate {100}] [get_ips blk_d_cache]
generate_target {instantiation_template} [get_files vivado/vivado.srcs/sources_1/ip/blk_d_cache/blk_d_cache.xci]
update_compile_order -fileset sources_1
set_property generate_synth_checkpoint false [get_files  vivado/vivado.srcs/sources_1/ip/blk_d_cache/blk_d_cache.xci]
generate_target all [get_files  vivado/vivado.srcs/sources_1/ip/blk_d_cache/blk_d_cache.xci]
export_ip_user_files -of_objects [get_files vivado/vivado.srcs/sources_1/ip/blk_d_cache/blk_d_cache.xci] -no_script -sync -force -quiet
export_simulation -of_objects [get_files vivado/vivado.srcs/sources_1/ip/blk_d_cache/blk_d_cache.xci] -directory vivado/vivado.ip_user_files/sim_scripts -ip_user_files_dir vivado/vivado.ip_user_files -ipstatic_source_dir vivado/vivado.ip_user_files/ipstatic -lib_map_path [list {modelsim=vivado/vivado.cache/compile_simlib/modelsim} {questa=vivado/vivado.cache/compile_simlib/questa} {ies=vivado/vivado.cache/compile_simlib/ies} {xcelium=vivado/vivado.cache/compile_simlib/xcelium} {vcs=vivado/vivado.cache/compile_simlib/vcs} {riviera=vivado/vivado.cache/compile_simlib/riviera}] -use_ip_compiled_libs -force -quiet
create_ip -name blk_mem_gen -vendor xilinx.com -library ip -version 8.4 -module_name blk_d_cache_tags
set_property -dict [list CONFIG.Component_Name {blk_d_cache_tags} CONFIG.Memory_Type {Simple_Dual_Port_RAM} CONFIG.Write_Width_A {20} CONFIG.Write_Depth_A {64} CONFIG.Read_Width_A {20} CONFIG.Operating_Mode_A {NO_CHANGE} CONFIG.Enable_A {Always_Enabled} CONFIG.Write_Width_B {20} CONFIG.Read_Width_B {20} CONFIG.Enable_B {Always_Enabled} CONFIG.Register_PortA_Output_of_Memory_Primitives {false} CONFIG.Register_PortB_Output_of_Memory_Primitives {false} CONFIG.Port_B_Clock {100} CONFIG.Port_B_Enable_Rate {100}] [get_ips blk_d_cache_tags]
generate_target {instantiation_template} [get_files vivado/vivado.srcs/sources_1/ip/blk_d_cache_tags/blk_d_cache_tags.xci]
update_compile_order -fileset sources_1
set_property generate_synth_checkpoint false [get_files  vivado/vivado.srcs/sources_1/ip/blk_d_cache_tags/blk_d_cache_tags.xci]
generate_target all [get_files  vivado/vivado.srcs/sources_1/ip/blk_d_cache_tags/blk_d_cache_tags.xci]
export_ip_user_files -of_objects [get_files vivado/vivado.srcs/sources_1/ip/blk_d_cache_tags/blk_d_cache_tags.xci] -no_script -sync -force -quiet
export_simulation -of_objects [get_files vivado/vivado.srcs/sources_1/ip/blk_d_cache_tags/blk_d_cache_tags.xci] -directory vivado/vivado.ip_user_files/sim_scripts -ip_user_files_dir vivado/vivado.ip_user_files -ipstatic_source_dir vivado/vivado.ip_user_files/ipstatic -lib_map_path [list {modelsim=vivado/vivado.cache/compile_simlib/modelsim} {questa=vivado/vivado.cache/compile_simlib/questa} {ies=vivado/vivado.cache/compile_simlib/ies} {xcelium=vivado/vivado.cache/compile_simlib/xcelium} {vcs=vivado/vivado.cache/compile_simlib/vcs} {riviera=vivado/vivado.cache/compile_simlib/riviera}] -use_ip_compiled_libs -force -quiet
create_bd_design "riscv_soc"
update_compile_order -fileset sources_1
startgroup
create_bd_cell -type ip -vlnv xilinx.com:ip:processing_system7:5.5 processing_system7_0
endgroup
apply_bd_automation -rule xilinx.com:bd_rule:processing_system7 -config {make_external "FIXED_IO, DDR" apply_board_preset "1" Master "Disable" Slave "Disable" }  [get_bd_cells processing_system7_0]
startgroup
set_property -dict [list CONFIG.PCW_FPGA0_PERIPHERAL_FREQMHZ {55} CONFIG.PCW_USE_S_AXI_GP0 {1} CONFIG.PCW_USE_S_AXI_HP0 {1} CONFIG.PCW_QSPI_PERIPHERAL_ENABLE {0} CONFIG.PCW_QSPI_GRP_SINGLE_SS_ENABLE {1} CONFIG.PCW_ENET0_PERIPHERAL_ENABLE {0} CONFIG.PCW_SD0_PERIPHERAL_ENABLE {0} CONFIG.PCW_USB0_PERIPHERAL_ENABLE {0}] [get_bd_cells processing_system7_0]
endgroup
create_bd_cell -type module -reference core core_0
startgroup
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config { Clk_master {Auto} Clk_slave {Auto} Clk_xbar {Auto} Master {/core_0/peripheral} Slave {/processing_system7_0/S_AXI_GP0} ddr_seg {Auto} intc_ip {New AXI Interconnect} master_apm {0}}  [get_bd_intf_pins processing_system7_0/S_AXI_GP0]
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config { Clk_master {Auto} Clk_slave {Auto} Clk_xbar {Auto} Master {/core_0/dPort} Slave {/processing_system7_0/S_AXI_HP0} ddr_seg {Auto} intc_ip {New AXI Interconnect} master_apm {0}}  [get_bd_intf_pins processing_system7_0/S_AXI_HP0]
endgroup
disconnect_bd_net /rst_ps7_0_55M_peripheral_aresetn [get_bd_pins core_0/reset]
connect_bd_net [get_bd_pins core_0/reset] [get_bd_pins rst_ps7_0_55M/peripheral_reset]
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config { Clk_master {/processing_system7_0/FCLK_CLK0 (55 MHz)} Clk_slave {/processing_system7_0/FCLK_CLK0 (55 MHz)} Clk_xbar {/processing_system7_0/FCLK_CLK0 (55 MHz)} Master {/core_0/iPort} Slave {/processing_system7_0/S_AXI_HP0} ddr_seg {Auto} intc_ip {/axi_mem_intercon_1} master_apm {0}}  [get_bd_intf_pins core_0/iPort]
create_bd_cell -type module -reference bootROM bootROM_0
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config { Clk_master {/processing_system7_0/FCLK_CLK0 (55 MHz)} Clk_slave {Auto} Clk_xbar {/processing_system7_0/FCLK_CLK0 (55 MHz)} Master {/core_0/iPort} Slave {/bootROM_0/req} ddr_seg {Auto} intc_ip {/axi_mem_intercon_1} master_apm {0}}  [get_bd_intf_pins bootROM_0/req]
disconnect_bd_net /rst_ps7_0_55M_peripheral_aresetn [get_bd_pins bootROM_0/reset]
connect_bd_net [get_bd_pins bootROM_0/reset] [get_bd_pins rst_ps7_0_55M/peripheral_reset]
create_bd_cell -type module -reference psClint psClint_0
startgroup
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config { Clk_master {/processing_system7_0/FCLK_CLK0 (55 MHz)} Clk_slave {Auto} Clk_xbar {/processing_system7_0/FCLK_CLK0 (55 MHz)} Master {/core_0/peripheral} Slave {/psClint_0/client} ddr_seg {Auto} intc_ip {/axi_mem_intercon} master_apm {0}}  [get_bd_intf_pins psClint_0/client]
apply_bd_automation -rule xilinx.com:bd_rule:axi4 -config { Clk_master {Auto} Clk_slave {Auto} Clk_xbar {Auto} Master {/processing_system7_0/M_AXI_GP0} Slave {/psClint_0/psMaster} ddr_seg {Auto} intc_ip {New AXI SmartConnect} master_apm {0}}  [get_bd_intf_pins psClint_0/psMaster]
endgroup
disconnect_bd_net /rst_ps7_0_55M_peripheral_aresetn [get_bd_pins psClint_0/reset]
connect_bd_net [get_bd_pins psClint_0/reset] [get_bd_pins rst_ps7_0_55M/peripheral_reset]
connect_bd_net [get_bd_pins core_0/MTIP] [get_bd_pins psClint_0/MTIP]
set_property range 1G [get_bd_addr_segs {core_0/dPort/SEG_bootROM_0_reg0}]
set_property offset 0x40000000 [get_bd_addr_segs {core_0/dPort/SEG_bootROM_0_reg0}]
set_property range 1G [get_bd_addr_segs {core_0/iPort/SEG_bootROM_0_reg0}]
set_property offset 0x40000000 [get_bd_addr_segs {core_0/iPort/SEG_bootROM_0_reg0}]
set_property range 256M [get_bd_addr_segs {core_0/peripheral/SEG_psClint_0_reg0}]
set_property range 512M [get_bd_addr_segs {core_0/peripheral/SEG_processing_system7_0_GP0_DDR_LOWOCM}]
set_property offset 0x20000000 [get_bd_addr_segs {core_0/peripheral/SEG_processing_system7_0_GP0_DDR_LOWOCM}]
set_property offset 0x00000000 [get_bd_addr_segs {core_0/peripheral/SEG_psClint_0_reg0}]
startgroup
make_bd_pins_external  [get_bd_pins core_0/core_sample0]
endgroup
startgroup
make_bd_pins_external  [get_bd_pins core_0/core_sample1]
endgroup
startgroup
make_bd_pins_external  [get_bd_pins psClint_0/STANDBY]
endgroup
startgroup
make_bd_pins_external  [get_bd_pins psClint_0/RUNNING]
endgroup
add_files -fileset constrs_1 -norecurse /home/kaveesha/Documents/github/FYP-18-RISCV-Core/src/main/resources/zynq/bootrom_linux.xdc
import_files -fileset constrs_1 /home/kaveesha/Documents/github/FYP-18-RISCV-Core/src/main/resources/zynq/bootrom_linux.xdc
validate_bd_design
make_wrapper -files [get_files vivado/vivado.srcs/sources_1/bd/riscv_soc/riscv_soc.bd] -top
add_files -norecurse vivado/vivado.srcs/sources_1/bd/riscv_soc/hdl/riscv_soc_wrapper.v
update_compile_order -fileset sources_1
# Disabling source management mode.  This is to allow the top design properties to be set without GUI intervention.
set_property source_mgmt_mode None [current_project]
set_property top riscv_soc_wrapper [current_fileset]
# Re-enabling previously disabled source management mode.
set_property source_mgmt_mode All [current_project]
update_compile_order -fileset sources_1
launch_runs impl_1 -to_step write_bitstream -jobs 12
wait_on_run impl_1
write_hw_platform -fixed -force  -include_bit -file vivado/riscv_soc_wrapper.xsa
