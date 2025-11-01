package test;

import vibesafe4j.Func;

interface Greeting {

	@Func("""
			 return a greeting String

			 >>> greet("Alice")
			 'Hello, Alice!'

			 >>> greet("Chandra")
			 'Hello, Chandra!'

			""")
	String greet(String name);

}
