package net.zomis.duga

import net.zomis.duga.chat.BotConfiguration
import net.zomis.duga.chat.StackExchangeChatBot
import net.zomis.duga.chat.WebhookParameters
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.env.Environment

class DugaBot implements InitializingBean {

    @Autowired
    Environment environment

    private StackExchangeChatBot bot

    def postChat(String message) {
        this.postChat(WebhookParameters.toRoom('16134'), [message])
    }

    def postSingle(WebhookParameters params, String message) {
        this.postChat(params, [message])
    }

    def postChat(WebhookParameters params, List<String> messages) {
        messages.each {
            println "postChat $params: $it"
        }
        bot.postMessages(params, messages)
    }

    @Override
    void afterPropertiesSet() throws Exception {
        bot = new StackExchangeChatBot(new BotConfiguration().init(environment))
    }

    String fkey() {
        bot.getFKey()
    }

}
