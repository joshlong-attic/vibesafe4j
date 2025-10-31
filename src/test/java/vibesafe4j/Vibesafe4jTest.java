package vibesafe4j;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
class App {

    @Bean
    ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}

@SpringBootTest
class Vibesafe4jTest {

    @Test
    void build(@Autowired ChatClient ai) throws Exception {
        var greetingsInstance = Vibesafe4j.build(prompt -> ai.prompt(prompt).call().content(), Greeting.class);
        var strResult = greetingsInstance.greet("world").toLowerCase();
        Assertions.assertTrue(strResult.contains("hello"));
        Assertions.assertTrue(strResult.contains("world"));
        IO.println(strResult);
    }

    @Test
    void atoms(@Autowired ChatClient ai) throws Exception {
        var result = Vibesafe4j.sourceFor(prompt -> ai.prompt(prompt).call().content(), Greeting.class);
        Assertions.assertFalse(result.code().isBlank());
        Assertions.assertEquals("GreetingImpl", result.className());
        IO.println(result);
        var clzz = Vibesafe4j.classFor(result);
        var instance = clzz.getDeclaredConstructors()[0].newInstance();
        var greetings = clzz.getMethod("greet", String.class)
                .invoke(instance, "world");
        Assertions.assertInstanceOf(String.class, greetings);
        Assertions.assertEquals("Hello, world!", greetings);
    }
}