package test;

import vibesafe4j.Func;

interface Greeting {

	@Func("""
			 return a greeting String

			 >>> greet("Alice")
			 'Hello, Alice!'
			 >>> greet("ni hao")
			 'Hello, ni hao '
			""")
	String greet(String name);

}
