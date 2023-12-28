set_property -dict { PACKAGE_PIN Y11   IOSTANDARD LVCMOS33 } [get_ports { STANDBY_0 }]; #IO_L18N_T2_13 Sch=led5_r
set_property -dict { PACKAGE_PIN T5    IOSTANDARD LVCMOS33 } [get_ports { RUNNING_0 }]; #IO_L19P_T3_13 Sch=led5_g

set_property -dict { PACKAGE_PIN M14   IOSTANDARD LVCMOS33 } [get_ports { core_sample0_0 }]; #IO_L23P_T3_35 Sch=led[0]
set_property -dict { PACKAGE_PIN M15   IOSTANDARD LVCMOS33 } [get_ports { core_sample1_0 }]; #IO_L23N_T3_35 Sch=led[1]