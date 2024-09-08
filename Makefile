idea:
	mill -i mill.idea.GenIdea/idea

init:
	git submodule update --init
	cd rocket-chip && git submodule update --init cde hardfloat

comp:
	mill -i rocketgen.compile

rtl:
	@mkdir -p build/rtl
	mill -i rocketgen.runMain rocketgen.TopMain --full-stacktrace -td build/rtl --target systemverilog --split-verilog