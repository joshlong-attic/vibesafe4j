package vibesafe4j;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootApplication
class App {
}

@SpringBootTest
class Vibesafe4jTest {

    @Test
    void build(@Autowired ChatClient.Builder builder) throws Exception {
        var ai = builder.build();
        var result = Vibesafe4j.sourceFor(prompt -> ai.prompt(prompt).call().content(), Greeting.class);
        Assertions.assertFalse(result.code().isBlank());
        Assertions.assertEquals("Greeting", result.className());

        var clzz = Vibesafe4j.classFor(result);
        var instance = clzz.getDeclaredConstructors()[0].newInstance();
        var greetings = clzz.getMethod("greet", String.class)
                .invoke(instance, "world");
        Assertions.assertInstanceOf(String.class, greetings);
        Assertions.assertEquals("Hello, world!", greetings);
    }

}

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

