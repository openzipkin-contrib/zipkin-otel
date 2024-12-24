const {OpenAI} = require('openai');

async function main() {
    const openai = new OpenAI();
    const result = await openai.chat.completions.create({
        model: 'gpt-4o-mini',
        messages: [
            {role: 'user', content: 'Write a short poem on OpenTelemetry.'},
        ],
    });
    console.log(result.choices[0]?.message?.content);
}

main()