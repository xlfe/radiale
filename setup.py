from distutils.core import setup

setup(
    name='radiale',
    packages=['radiale'],
    version='0.3',
    license='EPL',
    description='radiale',
    url='https://github.com/xlfe/radiale',
    keywords=['home-automation'],
    install_requires=[
        'websockets',
        'aiohttp',
        'pychromecast',
        'aioesphomeapi',
        'zeroconf',
        'bcoding',
        'asyncio-mqtt'
    ],
    classifiers=[
     'Development Status :: 3 - Alpha',
     'Intended Audience :: Developers',
     'Topic :: Home Automation',
     'License :: OSI Approved :: Eclipse Public License 2.0 (EPL-2.0)',
     'Programming Language :: Python :: 3.9',
    ]
)

