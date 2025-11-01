package vibesafe4j.spring;

/**
 * the flimsiest of indirection between the Vibesafe4j project and whatever choice of AI
 * client you have. (we'll assume Spring AI)
 */
public interface AiClient {

	String call(String prompt);

}
