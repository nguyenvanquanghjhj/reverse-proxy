"""V6 store/proxy acceptance against a new private PostgreSQL schema, never the review store."""
import argparse, concurrent.futures as futures, hashlib, json, os, secrets, sys, time
from pathlib import Path
from lab import Lab, BASE, PORTS, fetch, get, ROOT
import store

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--out',type=Path,required=True);args=parser.parse_args()
    os.chdir(ROOT);store.configure(ROOT/'.env.store');lab=None;checks=[]
    def check(value,label):
        checks.append({'check':label,'passed':bool(value)})
        if not value:raise AssertionError(label)
    try:
        lab=Lab.__new__(Lab);lab.__init__(args.out.resolve());lab.proxy('ROUND_ROBIN','acceptance')
        catalog=json.loads((ROOT/'src/main/resources/web/store/images/real/catalog.json').read_text(encoding='utf-8'))['items']
        actual=get(BASE+'/api/store/products?limit=24')
        check(actual['total']==12,'12 real phone models in catalog')
        products=[]
        for index,expected in enumerate(catalog,1):
            product=get(BASE+'/api/store/products/'+str(index));products.append(product)
            check(product['name']==expected['name'],'verified model name '+str(index))
            images=[f for f in product['files'] if f['kind']=='IMAGE']
            check([f['sha256'] for f in images]==[f['sha256'] for f in expected['images']],'photographs match source manifest '+str(index))
            for image in images:
                for port in PORTS:
                    code,data,_=fetch(f'http://127.0.0.1:{port}'+image['url'])
                    check(code==200 and hashlib.sha256(data).hexdigest()==image['sha256'],'shared image integrity '+str(index)+' backend '+str(port))
        headers={'Content-Type':'application/json','Origin':BASE,'X-CSRF-Token':lab.session['csrf'],'Cookie':lab.cookie}
        body={'recipient':'QA buyer','phone':'0900000000','address':'Private acceptance test address','note':'V6 isolated test',
              'payment_method':'COD','guest_token':secrets.token_hex(32),'items':[{'variant_id':1,'quantity':1}]}
        key=secrets.token_hex(32);h={**headers,'Idempotency-Key':key}
        before=lab.sql('SELECT (SELECT count(*) FROM st_orders) orders,(SELECT stock FROM st_variants WHERE id=1) stock')[0]
        def submit(index):return fetch(f'http://127.0.0.1:{PORTS[index%3]}/api/store/orders',body,h,'POST')
        with futures.ThreadPoolExecutor(max_workers=12) as pool:replies=list(pool.map(submit,range(24)))
        ids={json.loads(data).get('id') for code,data,_ in replies}
        check(len(ids)==1 and all(code in (200,201) for code,_,_ in replies),'24 same-key requests across 3 JVMs create one order')
        after=lab.sql('SELECT (SELECT count(*) FROM st_orders) orders,(SELECT stock FROM st_variants WHERE id=1) stock')[0]
        check(after['orders']==before['orders']+1 and after['stock']==before['stock']-1,'SQL order count and stock decrement exactly once')
        order=next(iter(ids));check(fetch(BASE+'/api/store/orders/'+order)[0] in (401,403,404),'order ownership enforced')
        check(fetch(BASE+'/api/store/orders/'+order,headers={'X-Order-Token':body['guest_token']})[0]==200,'guest capability retrieves own order')
        lab.sql('UPDATE st_variants SET stock=1 WHERE id=1')
        def last_unit(i):return fetch(f'http://127.0.0.1:{PORTS[i%3]}/api/store/orders',body,{**h,'Idempotency-Key':secrets.token_hex(32)},'POST')[0]
        with futures.ThreadPoolExecutor(max_workers=12) as pool:codes=list(pool.map(last_unit,range(24)))
        check(codes.count(201)==1 and codes.count(409)==23,'24 concurrent checkouts cannot oversell final unit')
        check(lab.sql('SELECT stock FROM st_variants WHERE id=1')[0]['stock']==0,'SQL stock never negative')
        lab.sql('UPDATE st_variants SET stock=100000 WHERE id=1')
        check(fetch(BASE+'/api/store/admin/dashboard',headers={'Cookie':lab.cookie})[0] in (401,403),'admin server-side authorization')
        _,admin_guest,admin_headers=fetch(BASE+'/api/store/session');admin_guest=json.loads(admin_guest)
        admin_cookie=next(v for k,v in admin_headers.items() if k.lower()=='set-cookie').split(';',1)[0]
        code,data,rh=fetch(BASE+'/api/store/auth/login',{'email':lab.env['STORE_ADMIN_EMAIL'],'password':lab.env['STORE_ADMIN_PASSWORD']},{'Content-Type':'application/json','Origin':BASE,'X-CSRF-Token':admin_guest['csrf'],'Cookie':admin_cookie},'POST')
        identity=json.loads(data);check(code==200 and identity['user']['role']=='ADMIN','admin login')
        cookie=next(v for k,v in rh.items() if k.lower()=='set-cookie').split(';',1)[0]
        ah={'Content-Type':'application/json','Origin':BASE,'X-CSRF-Token':identity['csrf'],'Cookie':cookie}
        for port in PORTS:check(fetch(f'http://127.0.0.1:{port}/api/store/admin/dashboard',headers={'Cookie':cookie})[0]==200,'session continuity on '+str(port))
        version=get(BASE+'/api/store/availability')['version']
        disabled={'sales_enabled':False,'downloads_enabled':False,'uploads_enabled':False,'reason':'Private test pause','version':version}
        code,data,_=fetch(BASE+'/api/store/admin/operations',disabled,ah,'PUT');check(code==200,'shared service controls update')
        check(fetch(BASE+'/api/store/orders',body,{**headers,'Idempotency-Key':secrets.token_hex(32)},'POST')[0]==423,'sales pause rejects NEW order')
        check(fetch(BASE+'/api/store/orders',body,h,'POST')[0]==200,'committed idempotent replay survives sales pause')
        check(fetch(BASE+lab.pdf)[0]==423,'PDF pause enforced')
        check(fetch(BASE+lab.product['files'][0]['url'])[0]==200,'image remains available during PDF pause')
        check(fetch(BASE+'/api/store/admin/products/1/files?kind=PDF',b'%PDF-1.4\n%%EOF',{'Origin':BASE,'X-CSRF-Token':identity['csrf'],'Cookie':cookie,'Content-Type':'application/pdf','X-File-Name':'test.pdf'},'POST')[0]==423,'PDF upload pause enforced')
        enabled={**disabled,'sales_enabled':True,'downloads_enabled':True,'uploads_enabled':True,'version':json.loads(data)['version']}
        check(fetch(BASE+'/api/store/admin/operations',enabled,ah,'PUT')[0]==200,'service controls resume')
        for file in lab.product['files']:
            status,data,_=fetch(BASE+file['url']);check(status==200 and len(data)==file['size_bytes'] and hashlib.sha256(data).hexdigest()==file['sha256'],'file byte integrity '+file['kind'])
        # A real dependency fault on just B2 must not open its global circuit.
        fetch('http://127.0.0.1:19002/__test/store-fault?pdf_failure=true',b'',{'X-Test-Token':lab.fault},'POST')
        for _ in range(36):fetch(BASE+lab.pdf)
        metrics=get(BASE+'/__proxy/metrics')
        check(all(b['circuitState']=='CLOSED' and b['state']=='UP' for b in metrics['backends']),'PDF-only dependency failure does not poison global backend health')
        check(fetch(BASE+'/api/store/products')[0]==200 and lab.request('ORDER')[0]==201,'catalog and orders work while B2 PDF fails')
        fetch('http://127.0.0.1:19002/__test/store-fault?pdf_failure=false',b'',{'X-Test-Token':lab.fault},'POST')
        check(fetch(BASE+'/__test/store-fault',b'',{'X-Test-Token':lab.fault},'POST')[0]==403,'fault controls blocked through proxy')
        # Drain exclusion is atomic; let a slow, already leased PDF finish.
        fetch('http://127.0.0.1:19001/__test/store-fault?scope=PDF&delay_ms=1200',b'',{'X-Test-Token':lab.fault},'POST')
        with futures.ThreadPoolExecutor(max_workers=3) as pool:
            pending=[pool.submit(fetch,BASE+lab.pdf) for _ in range(3)]
            deadline=time.monotonic()+3
            while get(BASE+'/__proxy/metrics')['backends'][0]['inFlight']==0:
                if time.monotonic()>deadline:raise AssertionError('held PDF lease not observed')
                time.sleep(.02)
            check(fetch(BASE+'/__proxy/backend-drain',b'',{'X-Proxy-Action':'set-backend-draining','X-Proxy-Target':'127.0.0.1:19001','X-Proxy-Draining':'true'},'POST')[0]==200,'operator drain marks backend ineligible')
            selected=[fetch(BASE+'/work?cost=1')[2] for _ in range(12)]
            check(all('19001' not in json.dumps(h) for h in selected),'new requests avoid draining backend')
            check(all(f.result()[0]==200 for f in pending),'existing PDF leases finish during drain')
        fetch('http://127.0.0.1:19001/control?draining=true')
        check(get(BASE+'/__proxy/metrics')['backends'][0]['draining'],'draining remains observable')
        fetch('http://127.0.0.1:19001/control?draining=false')
        fetch(BASE+'/__proxy/backend-drain',b'',{'X-Proxy-Action':'set-backend-draining','X-Proxy-Target':'127.0.0.1:19001','X-Proxy-Draining':'false'},'POST')
        fetch('http://127.0.0.1:19001/__test/store-fault?delay_ms=0',b'',{'X-Test-Token':lab.fault},'POST')
        time.sleep(.6)
        # Real browser journey and responsive/print evidence from the private dataset.
        from playwright.sync_api import sync_playwright
        with sync_playwright() as p:
            browser=p.chromium.launch(channel='msedge',headless=True);context=browser.new_context(viewport={'width':1440,'height':1000});page=context.new_page();errors=[]
            page.on('pageerror',lambda e:errors.append(str(e)));shots=lab.out/'screens';shots.mkdir()
            # Renaming/reordering media must not move the wrong attribution/caption onto another image.
            original_photo=next(f for f in products[0]['files'] if f['kind']=='IMAGE')
            edit_url=BASE+'/api/store/admin/files/'+original_photo['id']
            check(fetch(edit_url,{'display_name':'QA renamed photograph','position':3},ah,'PATCH')[0]==200,'admin can rename and reorder a photograph')
            try:
                live=get(BASE+'/api/store/products/1');first_photo=next(f for f in live['files'] if f['kind']=='IMAGE')
                expected_caption=next(f['caption'] for f in live['source_metadata']['images'] if f['sha256']==first_photo['sha256'])
                page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle');page.locator('[data-image="0"]').click();page.locator('#main-image').evaluate('(image)=>image.decode()')
                page.locator('.gallery').screenshot(path=str(shots/'gallery-reordered.png'))
                check(page.locator('#main-image').get_attribute('alt')==expected_caption and page.locator('.photo-caption').inner_text().startswith(expected_caption),'caption and attribution follow image hash after rename/reorder')
            finally:
                check(fetch(edit_url,{'display_name':original_photo['display_name'],'position':original_photo['position']},ah,'PATCH')[0]==200,'restore private gallery order')
            for product in products:
                for width in (390,1440):
                    page.set_viewport_size({'width':width,'height':1000})
                    page.goto(BASE+'/shop/products/'+str(product['id']));page.wait_for_load_state('networkidle')
                    check(page.locator('h1').inner_text()==product['name'],'rendered model '+str(product['id'])+' '+str(width))
                    for button in page.locator('[data-image]').all():
                        button.click();page.locator('#main-image').evaluate('(image)=>image.decode()')
                        check(page.locator('#main-image').evaluate('(image)=>image.naturalWidth>0'),'gallery decodes '+str(product['id'])+' '+str(width))
                        if width==1440:page.locator('.gallery').screenshot(path=str(shots/f"phone-{product['id']}-photo-{button.get_attribute('data-image')}.png"))
                    page.screenshot(path=str(shots/f"phone-{product['id']}-{width}.png"),full_page=True)
            page.goto(BASE+'/shop/sources');page.wait_for_load_state('networkidle')
            check(page.locator('.product-sources').count()==12,'public manufacturer sources and per-photo license credits')
            page.screenshot(path=str(shots/'sources.png'),full_page=True)
            page.set_viewport_size({'width':1440,'height':1000})
            page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle')
            page.locator('button[value="buy"]').click();page.wait_for_url('**/shop/checkout');page.wait_for_load_state('networkidle')
            page.locator('[name="recipient"]').fill('Browser buyer');page.locator('[name="phone"]').fill('0900000000');page.locator('[name="address"]').fill('Browser private test address')
            page.screenshot(path=str(shots/'checkout.png'),full_page=True);page.locator('#checkout-form button[type="submit"]').click();page.wait_for_url('**/shop/orders/*');page.wait_for_load_state('networkidle')
            order_id=page.url.rsplit('/',1)[1];check(len(order_id)==36,'browser checkout committed order and navigated to confirmation');page.screenshot(path=str(shots/'confirmation.png'),full_page=True)
            check(lab.sql("SELECT count(*) total FROM st_orders WHERE id='"+order_id+"'")[0]['total']==1,'browser confirmation exists in PostgreSQL')
            v=lab.product['variants'][0];stale={'product_id':1,'variant_id':v['id'],'quantity':1,'name':lab.product['name'],'sku':v['sku'],'color':v['color'],'storage_gb':v['storage_gb'],'price_vnd':v['price_vnd'],'image':'/api/store/images/00000000-0000-0000-0000-000000000000'}
            page.evaluate('(line)=>localStorage.setItem("moc.cart.v1",JSON.stringify([line]))',stale);page.goto(BASE+'/shop/cart');page.wait_for_load_state('networkidle')
            expected_image=next(f['url'] for f in lab.product['files'] if f['kind']=='IMAGE')
            check(page.locator('.cart-item img').get_attribute('src')==expected_image,'old cart refreshes retired artwork URL in first render');page.screenshot(path=str(shots/'cart-refreshed.png'),full_page=True)
            page.evaluate('localStorage.removeItem("moc.cart.v1")');page.reload();page.wait_for_load_state('networkidle')
            # Commit the order but deliberately drop its HTTP response at the browser boundary.
            lost=browser.new_context();lost_page=lost.new_page();lost_page.goto(BASE+'/shop/products/1');lost_page.wait_for_load_state('networkidle')
            lost_page.locator('button[value="buy"]').click();lost_page.wait_for_url('**/shop/checkout');lost_page.wait_for_load_state('networkidle')
            for name,value in [('recipient','Unknown result buyer'),('phone','0900000000'),('address','Private lost response test address')]:lost_page.locator('[name="'+name+'"]').fill(value)
            before_unknown=lab.sql('SELECT count(*) total FROM st_orders')[0]['total']
            def drop_response(route):
                response=route.fetch();check(response.status==201,'unknown-result fixture actually committed');route.abort('failed')
            lost_page.route('**/api/store/orders',drop_response)
            lost_page.locator('#checkout-form button[type="submit"]').click();lost_page.locator('#retry-order').wait_for()
            lost_page.screenshot(path=str(shots/'unknown-result.png'),full_page=True)
            check(lost_page.locator('#checkout-form button[type="submit"]').is_disabled(),'UNKNOWN_RESULT prevents new submit')
            lost_page.unroute('**/api/store/orders',drop_response);lost_page.locator('#retry-order').click();lost_page.wait_for_url('**/shop/orders/*')
            check(lab.sql('SELECT count(*) total FROM st_orders')[0]['total']==before_unknown+1,'lost response replay does not create another order');lost.close()
            page.goto(BASE+'/shop/login?next=admin');page.wait_for_load_state('networkidle');page.locator('[name="email"]').fill(lab.env['STORE_ADMIN_EMAIL']);page.locator('[name="password"]').fill(lab.env['STORE_ADMIN_PASSWORD']);page.locator('#auth-form button[type="submit"]').click();page.wait_for_url(BASE+'/admin');page.wait_for_load_state('networkidle')
            page.goto(BASE+'/shop/search?q=zzzz-no-such-phone');page.wait_for_load_state('networkidle');check(page.locator('.product-card').count()==0,'empty search is an explicit state');page.screenshot(path=str(shots/'empty-search.png'),full_page=True)
            page.goto(BASE+'/shop/cart');page.wait_for_load_state('networkidle');check(page.locator('.empty').count()==1,'empty cart after checkout');page.screenshot(path=str(shots/'empty-cart.png'),full_page=True)
            page.goto(BASE+'/shop');page.wait_for_load_state('networkidle');context.set_offline(True)
            page.locator('a[href="/shop/products"]').first.click();page.locator('#retry-page').wait_for();page.screenshot(path=str(shots/'offline.png'),full_page=True)
            context.set_offline(False);page.locator('#retry-page').click();page.locator('.product-card').first.wait_for();check(page.locator('.product-card').count()>0,'offline state recovers without losing navigation')
            pending=[]
            def hold_catalog(route):pending.append(route)
            page.route('**/api/store/products?*',hold_catalog);page.goto(BASE+'/shop',wait_until='domcontentloaded');page.locator('.loading').wait_for();page.screenshot(path=str(shots/'loading.png'),full_page=True)
            page.wait_for_timeout(150);check(bool(pending),'loading fixture holds a real catalog request')
            for route in pending:route.continue_()
            page.unroute('**/api/store/products?*',hold_catalog)
            page.locator('.hero').wait_for();check(page.locator('.hero').count()==1,'loading resolves to live catalog')
            page.goto(BASE+'/shop/products/1');page.wait_for_load_state('networkidle');page.locator('button[value="buy"]').click();page.wait_for_url('**/shop/checkout');page.wait_for_load_state('networkidle')
            if page.locator('#new-checkout').count():
                check(page.locator('#checkout-form').count()==0,'confirmed intent cannot silently become another order')
                page.locator('#new-checkout').click();page.wait_for_url('**/shop/cart');page.locator('a[href="/shop/checkout"]').click();page.wait_for_url('**/shop/checkout');page.locator('#checkout-form').wait_for()
            page.locator('[name="phone"]').fill('abc');check(not page.locator('[name="phone"]').evaluate('(input)=>input.checkValidity()'),'phone validation rejects malformed input');page.screenshot(path=str(shots/'validation.png'),full_page=True)
            current=get(BASE+'/api/store/availability');paused={**enabled,'sales_enabled':False,'version':current['version']};check(fetch(BASE+'/api/store/admin/operations',paused,ah,'PUT')[0]==200,'pause fixture for customer UI')
            page.reload();page.wait_for_load_state('networkidle');check(page.locator('#checkout-form button[type="submit"]').is_disabled(),'customer checkout visibly disabled during sales pause');page.screenshot(path=str(shots/'sales-paused.png'),full_page=True)
            current=get(BASE+'/api/store/availability');fetch(BASE+'/api/store/admin/operations',{**enabled,'version':current['version']},ah,'PUT')
            routes=[('home','/shop'),('catalog','/shop/products'),('detail','/shop/products/1'),('cart','/shop/cart'),('account','/shop/account'),('admin','/admin'),('admin-products','/admin/products'),('admin-orders','/admin/orders'),('media','/admin/documents?product=1'),('operations','/admin/operations'),('dashboard','/__proxy/dashboard')]
            layout=[]
            for width in (320,390,768,1024,1440):
                page.set_viewport_size({'width':width,'height':1000})
                for name,path in routes:
                    page.goto(BASE+path);page.wait_for_load_state('networkidle')
                    # Load lazy artwork before full-page capture and print checks.
                    page.evaluate("async()=>{document.querySelectorAll('img[loading]').forEach(i=>i.loading='eager');await Promise.all([...document.images].map(i=>i.decode().catch(()=>{})));}")
                    page.screenshot(path=str(shots/(f'{name}-{width}.png')),full_page=True)
                    overflow=page.evaluate('document.documentElement.scrollWidth>innerWidth+1');layout.append({'route':path,'width':width,'overflow':overflow})
                    check(not overflow,'page overflow '+name+' '+str(width))
                    if width==1440 and name in ('home','media','dashboard'):
                        page.emulate_media(media='print');check(not page.locator('.skip').is_visible() if page.locator('.skip').count() else True,'print hides skip '+name)
                        page.pdf(path=str(shots/(name+'.pdf')),format='A4',print_background=True);page.emulate_media(media='screen')
            check(not errors,'no browser JavaScript errors');(lab.out/'layout.json').write_text(json.dumps(layout,indent=2))
            browser.close()
        metrics=get(BASE+'/__proxy/metrics');check(metrics['transport']['activeSockets']<=3,'bounded socket count after requests')
        check(metrics['transport']['connected']>0 and metrics['transport']['bytesRead']>0,'real transport instrumentation recorded')
        lab.java('com.example.store.StoreAdmin','audit')
        (lab.out/'acceptance.json').write_text(json.dumps({'passed':True,'checks':checks,'source':__import__('lab').fingerprint()},indent=2),encoding='utf-8')
    except BaseException:
        if lab and hasattr(lab,'out'):(lab.out/'acceptance.json').write_text(json.dumps({'passed':False,'checks':checks},indent=2))
        raise
    finally:
        if lab and hasattr(lab,'children'):lab.close()
    print('Acceptance passed:',len(checks))
if __name__=='__main__':main()
