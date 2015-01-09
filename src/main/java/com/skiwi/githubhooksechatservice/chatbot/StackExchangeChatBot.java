
package com.skiwi.githubhooksechatservice.chatbot;

import java.io.UncheckedIOException;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.ProtocolException;
import org.apache.http.client.RedirectStrategy;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;
import org.springframework.beans.factory.DisposableBean;

import com.gistlabs.mechanize.Resource;
import com.gistlabs.mechanize.document.html.HtmlDocument;
import com.gistlabs.mechanize.document.html.HtmlElement;
import com.gistlabs.mechanize.document.html.form.Form;
import com.gistlabs.mechanize.document.html.form.SubmitButton;
import com.gistlabs.mechanize.document.json.JsonDocument;
import com.gistlabs.mechanize.impl.MechanizeAgent;
import com.skiwi.githubhooksechatservice.mvc.configuration.Configuration;
import com.skiwi.githubhooksechatservice.mvc.controllers.WebhookParameters;

/**
 *
 * @author Frank van Heeswijk
 */
public class StackExchangeChatBot implements ChatBot, DisposableBean {
    private final static Logger LOGGER = Logger.getLogger(StackExchangeChatBot.class.getSimpleName());
    
    private static final int MAX_MESSAGE_LENGTH = 500;
    
	private final ExecutorService executorService = Executors.newSingleThreadExecutor();
	private final BlockingQueue<List<ChatMessage>> messagesQueue = new LinkedBlockingQueue<>();
    
    private final MechanizeAgent agent;
    
    private final Configuration configuration;
    
    private String chatFKey;
    
    public StackExchangeChatBot(final Configuration configuration) {
        this.configuration = configuration;
		this.executorService.submit(this::drainMessagesQueue);
        
        this.agent = new MechanizeAgent();
        
        this.agent.getClient().setRedirectStrategy(new RedirectStrategy() {
            @Override
            public boolean isRedirected(final HttpRequest httpRequest, final HttpResponse httpResponse, final HttpContext httpContext) throws ProtocolException {
                return (httpResponse.getStatusLine().getStatusCode() == 302);
            }

            @Override
            public HttpUriRequest getRedirect(final HttpRequest httpRequest, final HttpResponse httpResponse, final HttpContext httpContext) throws ProtocolException {
                httpRequest.getRequestLine().getProtocolVersion().getProtocol();
                String host = httpRequest.getFirstHeader("Host").getValue();
                String location = httpResponse.getFirstHeader("Location").getValue();
                String protocol = (httpRequest.getFirstHeader("Host").getValue().equals("openid.stackexchange.com")) ? "https" : "http";
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    LOGGER.info("Redirecting to " + location);
                    return new HttpGet(location);
                }
                else {
                    LOGGER.info("Redirecting to " + protocol + "://" + host + location);
                    return new HttpGet(protocol + "://" + host + location);
                }
            }
        });
        
        this.agent.getClient().addRequestInterceptor((request, context) -> {
            LOGGER.info("Request to " + request.getRequestLine().getUri());
            if (request.getRequestLine().getUri().equals("/login/global-fallback")) {
                request.addHeader("Referer", configuration.getRootUrl() + "/users/chat-login");
            }
        });
    }
    
    @Override
    public void start() {        
        loginOpenId();
        loginRoot();
        loginChat();
        
        String fkey = getFKey();
        this.chatFKey = fkey;
        LOGGER.info("Found fkey: " + fkey);
		
		if (configuration.getDeployGreetingOn()) {
			postMessage(configuration.getDeployGreetingText());
		}
    }
    
    private void loginOpenId() {
        HtmlDocument openIdLoginPage = agent.get("https://openid.stackexchange.com/account/login");
        Form loginForm = openIdLoginPage.forms().getAll().get(0);
        loginForm.get("email").setValue(configuration.getBotEmail());
        loginForm.get("password").setValue(configuration.getBotPassword());
        List<SubmitButton> submitButtons = loginForm.findAll("input[type=submit]", SubmitButton.class);
        HtmlDocument response = loginForm.submit(submitButtons.get(0));
        LOGGER.info(response.getTitle());
        LOGGER.info("OpenID login attempted.");
    }
    
    private void loginRoot() {
        HtmlDocument rootLoginPage = agent.get(configuration.getRootUrl() + "/users/login");
        Form loginForm = rootLoginPage.forms().getAll().get(rootLoginPage.forms().getAll().size() - 1);
        loginForm.get("openid_identifier").setValue("https://openid.stackexchange.com/");
        List<SubmitButton> submitButtons = loginForm.findAll("input[type=submit]", SubmitButton.class);
        HtmlDocument response = loginForm.submit(submitButtons.get(submitButtons.size() - 1));
        LOGGER.info(response.getTitle());
        LOGGER.info("Root login attempted.");
    }
    
    private void loginChat() {
        HtmlDocument chatLoginPage = agent.get(configuration.getRootUrl() + "/users/chat-login");
        Form loginForm = chatLoginPage.forms().getAll().get(chatLoginPage.forms().getAll().size() - 1);
        List<SubmitButton> submitButtons = loginForm.findAll("input[type=submit]", SubmitButton.class);
        HtmlDocument response = loginForm.submit(submitButtons.get(submitButtons.size() - 1));
        LOGGER.info(response.getTitle());
        LOGGER.info("Chat login attempted."); 
    }
    
    private String getFKey() {
        HtmlDocument joinFavoritesPage = agent.get(configuration.getChatUrl() + "/chats/join/favorite");
        Form joinForm = joinFavoritesPage.forms().getAll().get(joinFavoritesPage.forms().getAll().size() - 1);
        return joinForm.get("fkey").getValue();
    }
	
    @Override
    public void postMessages(WebhookParameters params, final List<String> messages) {
    	if (params == null) {
    		params = new WebhookParameters();
    	}
		params.useDefaultRoom(configuration.getRoomId());
		Objects.requireNonNull(messages, "messages");
		List<ChatMessage> shortenedMessages = new ArrayList<>();
		for (String message : messages) {
			String messageCopy = message;
			final String continuation = "...";
			while (messageCopy.length() > MAX_MESSAGE_LENGTH) {
				final int firstPart = MAX_MESSAGE_LENGTH - continuation.length();
				shortenedMessages.add(new ChatMessage(params, messageCopy.substring(0, firstPart) + continuation));
				messageCopy = messageCopy.substring(firstPart);
			}
			shortenedMessages.add(new ChatMessage(params, messageCopy));
		}
		messagesQueue.add(shortenedMessages);
	} 
	
    private void attemptPostMessageToChat(final ChatMessage message) {
		Objects.requireNonNull(message, "message");
		try {
			postMessageToChat(message);
		} catch (ChatThrottleException ex) {
			LOGGER.info("Sleeping for " + ex.getThrottleTiming() + " seconds, then reposting");
			try {
				TimeUnit.SECONDS.sleep(ex.getThrottleTiming());
			} catch (InterruptedException ex1) {
				Thread.currentThread().interrupt();
			}
			try {
				postMessageToChat(message);
			} catch (ChatThrottleException | ProbablyNotLoggedInException ex1) {
				LOGGER.log(Level.INFO, "Failed to post message on retry", ex1);
			}
		} catch (ProbablyNotLoggedInException ex) {
			LOGGER.info("Not logged in, logging in and then reposting");
			start();
			try {
				postMessageToChat(message);
			} catch (ChatThrottleException | ProbablyNotLoggedInException ex1) {
				LOGGER.log(Level.INFO, "Failed to post message on retry", ex1);
			}
		}
	}
    
    private void postMessageToChat(final ChatMessage message) throws ChatThrottleException, ProbablyNotLoggedInException {
        Objects.requireNonNull(message, "message");
        Map<String, String> parameters = new HashMap<>();
        parameters.put("text", message.getMessage());
        parameters.put("fkey", this.chatFKey);
        try {
			Resource response = agent.post("http://chat.stackexchange.com/chats/" + message.getRoom() + "/messages/new", parameters);
            LOGGER.info(response.getTitle());
			if (response instanceof JsonDocument) {
				//success
			}
			else if (response instanceof HtmlDocument) {
				//failure
				HtmlDocument htmlDocument = (HtmlDocument)response;
				HtmlElement body = htmlDocument.find("body");
				if (body.getInnerHtml().contains("You can perform this action again in")) {
					int timing = Integer.parseInt(body.getInnerHtml()
						.replaceAll("You can perform this action again in", "")
						.replaceAll("seconds", "")
						.trim());
					throw new ChatThrottleException(timing);
				}
				else {
					System.out.println(body.getInnerHtml());
					throw new ProbablyNotLoggedInException();
				}
			}
			else {
				//even harder failure
				throw new IllegalStateException("unexpected response, response.getClass() = " + response.getClass());
			}
        } catch (UnsupportedEncodingException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    @Override
    public void stop() {
		if (configuration.getUndeployGoodbyeEnabled()) {
			postMessage(configuration.getUndeployGoodbyeText());
		}
		this.executorService.shutdown();
    }

	@Override
	public void destroy() throws Exception {
		this.stop();
	}
	
	private void drainMessagesQueue() {
		try {
			while (true) {
				postDrainedMessages(messagesQueue.take());
			}
		} catch (InterruptedException ex) {
			List<List<ChatMessage>> drainedMessages = new ArrayList<>();
			messagesQueue.drainTo(drainedMessages);
			drainedMessages.forEach(this::postDrainedMessages);
			Thread.currentThread().interrupt();
		}
	}
	
	private long lastPostedTime = 0L;
	private int currentBurst = 0;
	
	private void postDrainedMessages(final List<ChatMessage> messages) {
		Objects.requireNonNull(messages, "messages");
		LOGGER.fine("Attempting to post " + messages);
		if (currentBurst + messages.size() >= configuration.getChatMaxBurst() || System.currentTimeMillis() < lastPostedTime + configuration.getChatThrottle()) {
			long sleepTime = lastPostedTime + configuration.getChatThrottle() - System.currentTimeMillis();
			LOGGER.info("Sleeping for " + sleepTime + " milliseconds");
			try {
				TimeUnit.MILLISECONDS.sleep(sleepTime);
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			currentBurst = 0;
		}
		else {
			currentBurst += messages.size();
		}
		messages.forEach(message -> {
			try {
				TimeUnit.MILLISECONDS.sleep(configuration.getChatMinimumDelay());
			} catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			attemptPostMessageToChat(message);
		});
		lastPostedTime = System.currentTimeMillis();
	}
}
