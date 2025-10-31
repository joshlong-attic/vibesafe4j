# vibesafe4j

this is inpsired by the amazing https://github.com/julep-ai/vibesafe/ project. it's rough, but the intended usage is: 

```java

interface MyVibeFunctions {

  @Func ("""
    greet('Alice')
    >>> "hello, Alice"
  """)
  String greet(String name) ;

}

```

You should be able to ask for an implementation of that interface whose method implementations are satisfied by calling the LLM. 

```java
var funcs = generate(MyVibeFunctions.class); 
var response = funcs.greet("World");
IO.println(response) ; // should be "Hello, World"
```
