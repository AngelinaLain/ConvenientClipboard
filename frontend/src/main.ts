import {createApp} from 'vue'
import {createRouter, createWebHistory} from 'vue-router'
import App from './App.vue'
import Upload from './views/Upload.vue'
import Login from './views/Login.vue'
import Files from './views/Files.vue'
import Share from './views/Share.vue'
import Clipboard from './views/Clipboard.vue'
import Admin from './views/Admin.vue'
import {initialize, session} from './api'
import './style.css'

const router = createRouter({
    history: createWebHistory(), routes: [
        {path: '/', component: Upload}, {path: '/login', component: Login}, {
            path: '/files',
            component: Files,
            meta: {auth: true}
        },
        {path: '/s/:token', component: Share},
        {path: '/clipboard', component: Clipboard}, {path: '/clipboard/:id', component: Clipboard},
        {path: '/admin/login', component: Login, props: {admin: true}},
        {path: '/admin', component: Admin, meta: {admin: true}}, {path: '/:pathMatch(.*)*', redirect: '/'},
    ]
})
router.beforeEach(async to => {
    await initialize();
    if (to.meta.admin && session.user?.role !== 'ADMIN') return {path: '/admin/login', query: {next: to.fullPath}};
    if (to.meta.auth && !session.user) return {path: '/login', query: {next: to.fullPath}}
})
createApp(App).use(router).mount('#app')
