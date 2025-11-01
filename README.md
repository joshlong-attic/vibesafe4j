# vibesafe4j

this is inpsired by the amazing https://github.com/julep-ai/vibesafe/ project. 

The general usage is straightforward. Given the following interface:

```java

interface MyVibeFunctions {

  @Func ("""
    greet('Alice')
    >>> "hello, Alice"
  """)
  String greet(String name) ;

}

```


You can easily create an implementation of the interface with method implementations powered by an AI model of 
your choice, using the lower-level `Vibesafe4j` factory. Here, in a test, we're using Spring AI's auto-configured 
`ChatClient`.

```java


	@Test
	void build(@Autowired ChatClient ai) throws Exception {
		var greetingsInstance = Vibesafe4j.build(prompt -> ai.prompt(prompt).call().content(), Greeting.class);
		var strResult = greetingsInstance.greet("world").toLowerCase();
		Assertions.assertTrue(strResult.contains("hello"));
		Assertions.assertTrue(strResult.contains("world"));
		IO.println(strResult);
	}


```

Or, you can use the level Spring Boot component model to automatically detect interfaces with the `@Func` annotation 
and turn them into valid implementations that can be injected, with no configuration required.

```java

	@Test
	void auto(@Autowired Greeting greeting) {
		var response = greeting.greet("vibesafe4j");
		IO.println("response: " + response);
	}
```

## TODO 
* we need to figure out this AOT + GraalVM story