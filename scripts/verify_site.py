"""Browser acceptance for the authenticated storefront.

Preserves purchase, lost-response replay, source attribution, gallery, pause,
offline, loading, validation, responsive and print checks for its current routes.
"""
import hashlib
import json
import secrets
import time
from urllib.parse import urlsplit
from lab import BASE, fetch, get


def verify_browser(lab,check,products,admin_headers):
    from playwright.sync_api import sync_playwright
    with sync_playwright() as p:
        browser=p.chromium.launch(channel='msedge',headless=True)
        context=browser.new_context(viewport={'width':1440,'height':1000})
        page=context.new_page();errors=[]
        page.on('pageerror',lambda error:errors.append(str(error)))
        shots=lab.out/'screens';shots.mkdir()
        email='browser-'+secrets.token_hex(6)+'@example.test';password=secrets.token_urlsafe(24)
        page.goto(BASE+'/shop/products/1');page.wait_for_url('**/shop/login?*')
        check(page.locator('#auth-form').count()==1,'current storefront protects product UI with login')
        page.goto(BASE+'/shop/register')
        for name,value in [('name','Browser buyer'),('email',email),('password',password)]:
            page.locator('[name="'+name+'"]').fill(value)
        page.locator('#auth-form button[type="submit"]').click();page.wait_for_url(BASE+'/shop')
        page.locator('.item').first.wait_for()
        check(page.locator('.item').count()==12,'real browser registration reaches all twelve models')
        def login(target,email,password,next_path):
            target.goto(BASE+'/shop/login?next='+next_path)
            target.locator('[name="email"]').fill(email);target.locator('[name="password"]').fill(password)
            target.locator('#auth-form button[type="submit"]').click()
            target.wait_for_url(BASE+('/admin' if next_path=='admin' else next_path))
            target.wait_for_load_state('networkidle')

        photo=next(f for f in products[0]['files'] if f['kind']=='IMAGE')
        edit=BASE+'/api/store/admin/files/'+photo['id']
        check(fetch(edit,{'display_name':'QA renamed photograph','position':3},admin_headers,'PATCH')[0]==200,
              'admin can rename and reorder a photograph')
        try:
            live=get(BASE+'/api/store/products/1');first=next(f for f in live['files'] if f['kind']=='IMAGE')
            credit=next(i for i in live['source_metadata']['images'] if i['sha256']==first['sha256'])
            page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle')
            page.locator('#photo').evaluate('(image)=>image.decode()')
            check(page.locator('#photo').get_attribute('src')==first['url']
                  and page.locator('#caption').inner_text().startswith(credit['caption'])
                  and page.locator('#caption a').get_attribute('href')==credit['license_url'],
                  'photo and attribution follow image hash after rename/reorder')
            page.locator('.gallery').screenshot(path=str(shots/'gallery-reordered.png'))
        finally:
            check(fetch(edit,{'display_name':photo['display_name'],'position':photo['position']},admin_headers,'PATCH')[0]==200,
                  'restore private gallery order')
        for product in products:
            for width in (390,1440):
                page.set_viewport_size({'width':width,'height':1000})
                page.goto(BASE+'/shop/products/'+str(product['id']));page.wait_for_load_state('networkidle')
                check(page.locator('h1').inner_text()==product['name'],'rendered model '+str(product['id'])+' '+str(width))
                page.locator('#photo').evaluate('(image)=>image.decode()')
                check(page.locator('#photo').evaluate('(image)=>image.naturalWidth>0'),'primary photo decodes '+str(product['id'])+' '+str(width))
                for button in page.locator('[data-photo]').all():
                    button.click();page.locator('#photo').evaluate('(image)=>image.decode()')
                    check(page.locator('#photo').evaluate('(image)=>image.naturalWidth>0'),'gallery decodes '+str(product['id'])+' '+str(width))
                    if width==1440:page.locator('.gallery').screenshot(path=str(shots/f"phone-{product['id']}-photo-{button.get_attribute('data-photo')}.png"))
                for source in product['source_metadata']['sources']:
                    check(page.locator('details a[href="'+source['url']+'"]').count()>0,
                          'manufacturer source retained on product '+str(product['id'])+' '+str(width))
                page.screenshot(path=str(shots/f"phone-{product['id']}-{width}.png"),full_page=True)
        page.set_viewport_size({'width':1440,'height':1000})
        page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle')
        for name,value in [('recipient','Browser buyer'),('phone','0900000000'),('address','Browser private test address')]:
            page.locator('[name="'+name+'"]').fill(value)
        page.screenshot(path=str(shots/'checkout.png'),full_page=True)
        page.locator('#order-submit').click();page.wait_for_url('**/shop/orders/*');page.wait_for_load_state('networkidle')
        order_id=urlsplit(page.url).path.rsplit('/',1)[1]
        check(len(order_id)==36,'browser checkout committed order and navigated to confirmation')
        check(lab.sql("SELECT count(*) total FROM st_orders WHERE id='"+order_id+"'")[0]['total']==1,'browser confirmation exists exactly once in PostgreSQL')
        page.screenshot(path=str(shots/'confirmation.png'),full_page=True)
        count=lab.sql('SELECT count(*) total FROM st_orders')[0]['total']
        stale={'product_id':1,'variant_id':1,'quantity':1,'image':'/api/store/images/00000000-0000-0000-0000-000000000000'}
        page.evaluate('(line)=>localStorage.setItem("moc.cart.v1",JSON.stringify([line]))',stale)
        page.goto(BASE+'/shop/cart');page.locator('.item').first.wait_for()
        check(page.locator('.item').count()==12,'retired cart route follows upstream product-list compatibility contract')
        page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle')
        check(page.locator('#photo').get_attribute('src')==photo['url'],'retired cart artwork cannot contaminate current purchase page')
        check(lab.sql('SELECT count(*) total FROM st_orders')[0]['total']==count,'reopening a confirmed product never auto-submits another order')

        lost=browser.new_context();lost_page=lost.new_page();login(lost_page,email,password,'/shop/products/1')
        for name,value in [('recipient','Unknown result buyer'),('phone','0900000000'),('address','Private lost response test address')]:
            lost_page.locator('[name="'+name+'"]').fill(value)
        before_unknown=lab.sql('SELECT count(*) total FROM st_orders')[0]['total']
        def drop_response(route):
            response=route.fetch();check(response.status==201,'unknown-result fixture actually committed');route.abort('failed')
        lost_page.route('**/api/store/orders',drop_response)
        lost_page.locator('#order-submit').click();lost_page.locator('#retry-order').wait_for()
        check(lost_page.locator('#order-submit').is_disabled(),'UNKNOWN_RESULT prevents a second submit')
        lost_page.screenshot(path=str(shots/'unknown-result.png'),full_page=True)
        lost_page.unroute('**/api/store/orders',drop_response);lost_page.locator('#retry-order').click()
        lost_page.wait_for_url('**/shop/orders/*')
        check(lab.sql('SELECT count(*) total FROM st_orders')[0]['total']==before_unknown+1,'lost response replay creates no duplicate order')
        lost.close()

        context.clear_cookies();login(page,lab.env['STORE_ADMIN_EMAIL'],lab.env['STORE_ADMIN_PASSWORD'],'admin')
        page.goto(BASE+'/shop?q=zzzz-no-such-phone');page.wait_for_load_state('networkidle')
        check(page.locator('.item').count()==0 and 'Không có sản phẩm' in page.locator('#status').inner_text()
              and 'zzzz-no-such-phone' in page.locator('#status').inner_text(),'empty search is explicit')
        page.screenshot(path=str(shots/'empty-search.png'),full_page=True)
        page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle')
        context.set_offline(True);page.locator('.nav a[href="/shop/orders"]').click();page.locator('#retry-page').wait_for()
        page.screenshot(path=str(shots/'offline.png'),full_page=True)
        context.set_offline(False);page.locator('#retry-page').click();page.wait_for_load_state('networkidle')
        page.locator('h1').filter(has_text='Đơn của tôi').wait_for()
        check(page.locator('#retry-page').count()==0,'offline state recovers through its real retry control')
        pending=[]
        def hold_catalog(route):pending.append(route)
        page.route('**/api/store/products?*',hold_catalog);page.goto(BASE+'/shop',wait_until='domcontentloaded')
        deadline=time.monotonic()+5
        while not pending and time.monotonic()<deadline:page.wait_for_timeout(50)
        check(bool(pending) and 'Đang tải' in page.locator('#status').inner_text(),'loading state holds a real catalog request')
        page.screenshot(path=str(shots/'loading.png'),full_page=True)
        for route in pending:route.continue_()
        page.unroute('**/api/store/products?*',hold_catalog);page.locator('.item').first.wait_for()
        check(page.locator('.item').count()==12,'loading resolves to live catalog')
        page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle')
        page.locator('[name="phone"]').fill('abc');page.locator('#order-submit').click()
        check(page.locator('[name="phone"]').get_attribute('aria-invalid')=='true','phone validation rejects malformed input before submission')
        page.screenshot(path=str(shots/'validation.png'),full_page=True)
        paused={'sales_enabled':False,'downloads_enabled':False,'uploads_enabled':False,'reason':'Browser private pause',
                'version':get(BASE+'/api/store/availability')['version']}
        check(fetch(BASE+'/api/store/admin/operations',paused,admin_headers,'PUT')[0]==200,'browser fixture pauses new orders')
        page.reload();page.wait_for_load_state('networkidle')
        check(page.locator('#order-submit').is_disabled(),'purchase button visibly disabled during sales pause')
        page.screenshot(path=str(shots/'sales-paused.png'),full_page=True)
        resumed={**paused,'sales_enabled':True,'downloads_enabled':True,'uploads_enabled':True,
                 'version':get(BASE+'/api/store/availability')['version']}
        check(fetch(BASE+'/api/store/admin/operations',resumed,admin_headers,'PUT')[0]==200,'browser fixture resumes service')
        routes=[('home','/shop'),('catalog','/shop/products'),('detail','/shop/products/1'),('cart','/shop/cart'),
                ('account','/shop/account'),('admin','/admin'),('admin-products','/admin/products'),
                ('admin-orders','/admin/orders'),('media','/admin/documents?product=1'),
                ('operations','/admin/operations'),('dashboard','/__proxy/dashboard')]
        layout=[]
        for width in (320,390,768,1024,1440):
            page.set_viewport_size({'width':width,'height':1000})
            for name,path in routes:
                page.goto(BASE+path);page.wait_for_load_state('networkidle')
                page.evaluate("async()=>{document.querySelectorAll('img[loading]').forEach(i=>i.loading='eager');await Promise.all([...document.images].map(i=>i.decode().catch(()=>{})));}")
                page.screenshot(path=str(shots/f'{name}-{width}.png'),full_page=True)
                overflow=page.evaluate('document.documentElement.scrollWidth>innerWidth+1')
                layout.append({'route':path,'width':width,'overflow':overflow})
                check(not overflow,'page overflow '+name+' '+str(width))
                if width==1440 and name in ('home','media','dashboard'):
                    page.emulate_media(media='print')
                    check(not page.locator('.skip').is_visible() if page.locator('.skip').count() else True,'print hides skip '+name)
                    page.pdf(path=str(shots/(name+'.pdf')),format='A4',print_background=True);page.emulate_media(media='screen')
        check(not errors,'no browser JavaScript errors')
        (lab.out/'layout.json').write_text(json.dumps(layout,indent=2),encoding='utf-8')
        browser.close()
