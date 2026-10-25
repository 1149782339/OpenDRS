import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import App from './App.vue'
import i18n from './i18n'
import router from './router'

const app = createApp(App)
app.use(createPinia())
app.use(router)
app.use(i18n)
// Element Plus 组件语言由 App.vue 里的 el-config-provider 按当前 i18n locale 动态给
app.use(ElementPlus)
app.mount('#app')
